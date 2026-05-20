package cpe.baldespompiers.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cpe.baldespompiers.client.FacilityClient;
import cpe.baldespompiers.client.FireClient;
import cpe.baldespompiers.client.RpEventClient;
import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.EmergencyEventDto;
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.model.type.VehicleType;
import cpe.baldespompiers.service.EmergencyManagerService;
import cpe.baldespompiers.service.FireService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class VehicleMovementThread {

    private static final Logger log = LoggerFactory.getLogger(VehicleMovementThread.class);

    private final VehicleClient vehicleClient;
    private final FireClient fireClient;
    private final FacilityClient facilityClient;
    private final RpEventClient rpEventClient;
    private final EmergencyManagerService emergencyManagerService;
    private final FireService fireService;

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    @Value("${movement.mode:teleport}")
    private String movementMode;

    @Value("${movement.step.size:0.0005}")
    private double stepSize;

    @Value("${movement.step.delay.ms:300}")
    private long stepDelayMs;

    @Value("${movement.fire.check.delay.ms:3000}")
    private long fireCheckDelayMs;

    @Value("${dispatch.give_up.fuel:10.0}")
    private float giveUpFuel;

    @Value("${dispatch.give_up.liquid:0.0}")
    private float giveUpLiquid;

    @Value("${dispatch.min.fuel:10.0}")
    private float minFuel;

    @Value("${dispatch.min.liquid:10.0}")
    private float minLiquid;

    @Value("${dispatch.ready.fuel:40.0}")
    private float readyFuel;

    @Value("${dispatch.ready.liquid:40.0}")
    private float readyLiquid;

    @Autowired
    public VehicleMovementThread(VehicleClient vehicleClient,
                                 FireClient fireClient,
                                 FacilityClient facilityClient,
                                 RpEventClient rpEventClient,
                                 @Lazy EmergencyManagerService emergencyManagerService,
                                 @Lazy FireService fireService) {
        this.vehicleClient           = vehicleClient;
        this.fireClient              = fireClient;
        this.facilityClient          = facilityClient;
        this.rpEventClient           = rpEventClient;
        this.emergencyManagerService = emergencyManagerService;
        this.fireService             = fireService;
    }

    // ── Exceptions internes ───────────────────────────────────────────────────
    private static final class InsufficientResourcesException extends RuntimeException {
        InsufficientResourcesException(String msg) { super(msg, null, true, false); }
    }
    private static final class ResumeMissionException extends RuntimeException {
        ResumeMissionException(String msg) { super(msg, null, true, false); }
    }

    public enum MovePhase { TO_FIRE, TO_FACILITY, MANUAL }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private boolean vehicleNeedsRecharge(VehicleDto v) {
        if (v.getFuelQuantity() < minFuel) return true;
        return v.getType() != null
                && v.getType().getLiquidCapacity() > 0
                && v.getLiquidQuantity() < minLiquid;
    }

    // ── moveVehicle (feux) ────────────────────────────────────────────────────
    @Async("vehicleMovementExecutor")
    public void moveVehicle(VehicleDto vehicle, FireDto initialFire,
                            String teamUuid, Runnable onDone) {
        FireDto currentFire = initialFire;
        boolean needsRecharge = false;
        try {
            while (true) {
                // Phase 1 : aller au feu
                movement_type(vehicle, teamUuid,
                        currentFire.getLon(), currentFire.getLat(), MovePhase.TO_FIRE);
                vehicle.setLon(currentFire.getLon());
                vehicle.setLat(currentFire.getLat());

                // Phase 2 : intervention
                emergencyManagerService.getVehicleStates()
                        .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);
                log.info("Véhicule {} arrivé sur feu #{} — attente extinction",
                        vehicle.getId(), currentFire.getId());
                waitForFireOut(currentFire.getId(), vehicle.getId());

                // Rafraîchir position et ressources
                VehicleDto refreshed = vehicleClient.getVehicleById(
                        String.valueOf(vehicle.getId()));
                if (refreshed == null) { needsRecharge = true; break; }
                vehicle.setLon(refreshed.getLon());
                vehicle.setLat(refreshed.getLat());

                if (vehicleNeedsRecharge(refreshed)) { needsRecharge = true; break; }
                if (emergencyManagerService.isRecallRequested(vehicle.getId())) {
                    needsRecharge = true; break;
                }

                // Chercher un autre feu directement
                List<FireDto> activeFires = fireClient.getAllFires();
                Optional<FireDto> next = fireService.findNextFireForVehicle(
                        refreshed, activeFires != null ? activeFires : List.of());

                if (next.isEmpty()) {
                    log.info("Véhicule {} — aucun feu disponible", vehicle.getId());
                    break;
                }

                FireDto nextFire = next.get();
                log.info("Véhicule {} : direct sur feu #{}", vehicle.getId(), nextFire.getId());
                fireService.claimFire(vehicle.getId(), currentFire.getId(), nextFire.getId());
                currentFire = nextFire;
            }

        } catch (InsufficientResourcesException e) {
            needsRecharge = true;
            log.warn("[Mission] Véhicule {} abandonne feu #{} — {}",
                    vehicle.getId(), currentFire.getId(), e.getMessage());
        } catch (InterruptedException | IOException e) {
            needsRecharge = true;
            Thread.currentThread().interrupt();
            log.error("[Move] Véhicule {} : {}", vehicle.getId(), e.getMessage());
        } catch (Exception e) {
            needsRecharge = true;
            log.error("[Move] Erreur véhicule {} : {}", vehicle.getId(), e.getMessage());
        } finally {
            try {
                fireService.releaseFire(currentFire.getId());
                if (needsRecharge) {
                    try {
                        returnToFacility(vehicle);
                        waitForRecharge(vehicle.getId());
                    } catch (ResumeMissionException e) {
                        log.info("Véhicule {} : retour interrompu", vehicle.getId());
                    }
                }
            } catch (Exception e) {
                log.error("[Move] Erreur retour véhicule {} : {}", vehicle.getId(), e.getMessage());
            }
            if (onDone != null) onDone.run();
        }
    }

    // ── moveVehicleToEvent (accidents/blessés) ────────────────────────────────
    @Async("vehicleMovementExecutor")
    public void moveVehicleToEvent(VehicleDto vehicle, EmergencyEventDto event,
                                   String teamUuid, Runnable onDone) {
        boolean needsRecharge = false;
        try {
            // Phase 1 : aller sur l'event
            movement_type(vehicle, teamUuid,
                    event.getLon(), event.getLat(), MovePhase.TO_FIRE);
            vehicle.setLon(event.getLon());
            vehicle.setLat(event.getLat());

            // Phase 2 : intervention
            emergencyManagerService.getVehicleStates()
                    .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);
            log.info("Véhicule {} arrivé sur event #{} — attente résolution",
                    vehicle.getId(), event.getId());
            waitForEventOut(event.getId(), vehicle.getId());

            // Vérifier ressources après intervention
            VehicleDto refreshed = vehicleClient.getVehicleById(
                    String.valueOf(vehicle.getId()));
            if (refreshed == null || vehicleNeedsRecharge(refreshed)) {
                needsRecharge = true;
            }

        } catch (InsufficientResourcesException e) {
            needsRecharge = true;
            log.warn("[Event] Véhicule {} abandonne event #{} — {}",
                    vehicle.getId(), event.getId(), e.getMessage());
        } catch (InterruptedException | IOException e) {
            needsRecharge = true;
            Thread.currentThread().interrupt();
            log.error("[Event] Véhicule {} : {}", vehicle.getId(), e.getMessage());
        } catch (Exception e) {
            needsRecharge = true;
            log.error("[Event] Erreur véhicule {} : {}", vehicle.getId(), e.getMessage());
        } finally {
            try {
                if (needsRecharge) {
                    try {
                        returnToFacility(vehicle);
                        waitForRecharge(vehicle.getId());
                    } catch (ResumeMissionException e) {
                        log.info("Véhicule {} : retour event interrompu", vehicle.getId());
                    }
                }
            } catch (Exception e) {
                log.error("[Event] Erreur retour véhicule {} : {}", vehicle.getId(), e.getMessage());
            }
            if (onDone != null) onDone.run();
        }
    }

    // ── Déplacement libre ─────────────────────────────────────────────────────
    @Async("vehicleMovementExecutor")
    public void moveTo(VehicleDto vehicle, double lon, double lat) {
        try {
            movement_type(vehicle, teamUuid, lon, lat, MovePhase.MANUAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Déplacement interrompu véhicule {}", vehicle.getId());
        } catch (IOException e) {
            log.error("Erreur déplacement véhicule {} : {}", vehicle.getId(), e.getMessage());
        }
    }

    // ── Sélection mode ────────────────────────────────────────────────────────
    private void movement_type(VehicleDto vehicle, String teamUuid,
                               double lon, double lat, MovePhase phase)
            throws InterruptedException, IOException {
        long vehicleDelay = computeStepDelay(vehicle.getType());
        if ("straight".equals(movementMode)) {
            moveToPoint(vehicle.getLon(), vehicle.getLat(),
                    lon, lat, vehicle.getId(), vehicleDelay, phase);
        } else if ("road".equals(movementMode)) {
            moveFollower(vehicle, lon, lat, teamUuid, vehicleDelay, phase);
        } else {
            teleport(vehicle.getId(), lon, lat);
        }
    }

    private long computeStepDelay(VehicleType type) {
        if (type == null) return stepDelayMs;
        float speed = type.getMaxSpeed();
        if (speed <= 0) return stepDelayMs;
        return (long) (stepDelayMs * 110.0 / speed);
    }

    // ── OSRM ─────────────────────────────────────────────────────────────────
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private void moveFollower(VehicleDto vehicle,
                              double targetLon, double targetLat,
                              String teamUuid, long vehicleDelay, MovePhase phase)
            throws IOException, InterruptedException {

        String url = "https://router.project-osrm.org/route/v1/driving/"
                + vehicle.getLon() + "," + vehicle.getLat()
                + ";" + targetLon + "," + targetLat
                + "?geometries=geojson&overview=full";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url)).GET()
                .timeout(Duration.ofSeconds(20)).build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("[OSRM] HTTP {} — fallback ligne droite", response.statusCode());
            moveToPoint(vehicle.getLon(), vehicle.getLat(),
                    targetLon, targetLat, vehicle.getId(), vehicleDelay, phase);
            return;
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (!"Ok".equals(root.path("code").asText())) {
            log.warn("[OSRM] {} — fallback ligne droite", root.path("code").asText());
            moveToPoint(vehicle.getLon(), vehicle.getLat(),
                    targetLon, targetLat, vehicle.getId(), vehicleDelay, phase);
            return;
        }

        JsonNode coordinates = root.path("routes").get(0)
                .path("geometry").path("coordinates");

        if (coordinates == null || !coordinates.isArray()) {
            log.warn("[OSRM] Pas de coordonnées — fallback ligne droite");
            moveToPoint(vehicle.getLon(), vehicle.getLat(),
                    targetLon, targetLat, vehicle.getId(), vehicleDelay, phase);
            return;
        }

        List<double[]> waypoints = new ArrayList<>();
        for (JsonNode coord : coordinates)
            waypoints.add(new double[]{ coord.get(0).asDouble(), coord.get(1).asDouble() });

        log.info("[OSRM] {} waypoints pour véhicule {}", waypoints.size(), vehicle.getId());

        for (int i = 1; i < waypoints.size(); i++) {
            double[] from = waypoints.get(i - 1);
            double[] to   = waypoints.get(i);
            moveToPoint(from[0], from[1], to[0], to[1], vehicle.getId(), vehicleDelay, phase);
        }

        if (!waypoints.isEmpty()) {
            double[] last = waypoints.get(waypoints.size() - 1);
            double d = Math.sqrt(Math.pow(targetLon - last[0], 2)
                    + Math.pow(targetLat - last[1], 2));
            if (d > stepSize)
                moveToPoint(last[0], last[1], targetLon, targetLat,
                        vehicle.getId(), vehicleDelay, phase);
        }

        log.info("[OSRM] Véhicule {} arrivé à destination", vehicle.getId());
    }

    // ── Téléportation ─────────────────────────────────────────────────────────
    private void teleport(Integer vehicleId, double lon, double lat) {
        vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(lon, lat));
    }

    // ── Interpolation pas à pas ───────────────────────────────────────────────
    private void moveToPoint(double fromLon, double fromLat,
                             double toLon, double toLat,
                             Integer vehicleId, long vehicleDelay,
                             MovePhase phase) throws InterruptedException {
        double currentLon = fromLon;
        double currentLat = fromLat;

        while (true) {
            if (phase == MovePhase.TO_FIRE
                    && emergencyManagerService.isRecallRequested(vehicleId))
                throw new InsufficientResourcesException("rappel actif");

            if (phase == MovePhase.TO_FACILITY
                    && !emergencyManagerService.isRecallMode()
                    && !emergencyManagerService.isRecallRequested(vehicleId))
                throw new ResumeMissionException("rappel terminé");

            double dLon = toLon - currentLon;
            double dLat = toLat - currentLat;
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);

            if (dist <= stepSize) {
                vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                        new Coord(toLon, toLat));
                Thread.sleep(vehicleDelay);
                break;
            }

            double ratio = stepSize / dist;
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            VehicleDto updated = vehicleClient.moveVehicle(teamUuid,
                    String.valueOf(vehicleId), new Coord(currentLon, currentLat));
            Thread.sleep(vehicleDelay);

            if (updated != null && updated.getFuelQuantity() < giveUpFuel)
                throw new InsufficientResourcesException(
                        "carburant insuffisant (fuel=" + updated.getFuelQuantity() + ")");
        }
    }

    // ── Attente extinction feu ────────────────────────────────────────────────
    private void waitForFireOut(Integer fireId, Integer vehicleId)
            throws InterruptedException {
        while (true) {
            if (emergencyManagerService.isRecallRequested(vehicleId))
                throw new InsufficientResourcesException("rappel actif");

            FireDto current = fireClient.getFireById(fireId);
            if (current == null || current.getIntensity() <= 0) break;

            VehicleDto vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
            if (vehicle != null && vehicle.getType() != null
                    && vehicle.getType().getLiquidCapacity() > 0
                    && vehicle.getLiquidQuantity() < giveUpLiquid)
                throw new InsufficientResourcesException(
                        "liquide insuffisant (liquid=" + vehicle.getLiquidQuantity() + ")");

            log.info("[Feu #{}] intensité = {}", fireId, current.getIntensity());
            Thread.sleep(fireCheckDelayMs);
        }
    }

    // ── Attente résolution event ──────────────────────────────────────────────
    private void waitForEventOut(Integer eventId, Integer vehicleId)
            throws InterruptedException {
        while (true) {
            if (emergencyManagerService.isRecallRequested(vehicleId))
                throw new InsufficientResourcesException("rappel actif");

            EmergencyEventDto current = rpEventClient.getEventById(eventId);
            if (current == null || current.getIntensity() <= 0) break;

            log.info("[Event #{}] intensité = {}", eventId, current.getIntensity());
            Thread.sleep(fireCheckDelayMs);
        }
    }

    // ── Retour caserne ────────────────────────────────────────────────────────
    private void returnToFacility(VehicleDto vehicle)
            throws InterruptedException, IOException {
        if (vehicle.getFacilityRefID() == null) return;
        VehicleDto current = vehicleClient.getVehicleById(
                String.valueOf(vehicle.getId()));
        if (current != null) {
            vehicle.setLon(current.getLon());
            vehicle.setLat(current.getLat());
        }
        FacilityDto facility = facilityClient.getFacilityById(
                String.valueOf(vehicle.getFacilityRefID()));
        if (facility == null) return;
        movement_type(vehicle, teamUuid,
                facility.getLon(), facility.getLat(), MovePhase.TO_FACILITY);
    }

    // ── Attente recharge ──────────────────────────────────────────────────────
    private void waitForRecharge(Integer vehicleId) throws InterruptedException {
        VehicleDto vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
        if (vehicle == null || vehicle.getType() == null) return;

        boolean needsFuel   = vehicle.getFuelQuantity() < readyFuel;
        boolean needsLiquid = vehicle.getType().getLiquidCapacity() > 0
                && vehicle.getLiquidQuantity() < readyLiquid;
        if (!needsFuel && !needsLiquid) return;

        log.info("Véhicule {} en recharge (fuel={} liquid={})",
                vehicleId, vehicle.getFuelQuantity(), vehicle.getLiquidQuantity());

        while (true) {
            Thread.sleep(fireCheckDelayMs);
            vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
            if (vehicle == null) break;
            boolean fuelOk   = vehicle.getFuelQuantity() >= readyFuel;
            boolean liquidOk = vehicle.getType().getLiquidCapacity() == 0
                    || vehicle.getLiquidQuantity() >= readyLiquid;
            if (fuelOk && liquidOk) break;
            log.info("[Recharge #{}] fuel={} liquid={}",
                    vehicleId, vehicle.getFuelQuantity(), vehicle.getLiquidQuantity());
        }
        log.info("Véhicule {} rechargé — prêt", vehicleId);
    }
}