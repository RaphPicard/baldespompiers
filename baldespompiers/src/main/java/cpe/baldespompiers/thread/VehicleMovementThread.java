package cpe.baldespompiers.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.client.FacilityClient;
import cpe.baldespompiers.client.FireClient;
import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.type.VehicleType;
import cpe.baldespompiers.service.EmergencyManagerService;
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

/**
 * Déplacement progressif d'un véhicule via @Async.
 * Un thread par véhicule, exécuté dans vehicleMovementExecutor.
 *
 * Modes (movement.mode dans application.properties) :
 *   "teleport" → PUT direct sur destination finale
 *   "straight" → interpolation ligne droite
 *   "road"     → waypoints OSRM
 */
@Component
public class VehicleMovementThread {

    private static final Logger log = LoggerFactory.getLogger(VehicleMovementThread.class);

    private final VehicleClient vehicleClient;
    private final FireClient fireClient;
    private final FacilityClient facilityClient;
    private final EmergencyManagerService emergencyManagerService;

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    @Value("${movement.mode:teleport}")
    private String movementMode;

    @Value("${movement.step.size:0.002}")
    private double stepSize;

    @Value("${movement.step.delay.ms:500}")
    private long stepDelayMs;

    @Value("${movement.fire.check.delay.ms:3000}")
    private long fireCheckDelayMs;

    @Autowired
    public VehicleMovementThread(VehicleClient vehicleClient,
                                 FireClient fireClient,
                                 FacilityClient facilityClient,
                                 @Lazy EmergencyManagerService emergencyManagerService) {
        this.vehicleClient = vehicleClient;
        this.fireClient = fireClient;
        this.facilityClient = facilityClient;
        this.emergencyManagerService = emergencyManagerService;
    }

    @Async("vehicleMovementExecutor")
    public void moveVehicle(VehicleDto vehicle, FireDto fire, String teamUuid, Runnable onDone) {
        try {
            // Phase 1 : aller au feu
            movement_type(vehicle, teamUuid, fire.getLon(), fire.getLat());
            // Mise à jour de la position pour que le trajet retour parte du bon endroit
            vehicle.setLon(fire.getLon());
            vehicle.setLat(fire.getLat());

            // Phase 2 : on est sur le feu, on attend qu'il soit éteint
            emergencyManagerService.getVehicleStates()
                    .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);
            log.info("Véhicule {} arrivé sur feu #{} — attente extinction", vehicle.getId(), fire.getId());

            waitForFireOut(fire.getId());

            // Phase 3 : retour à la caserne
            returnToFacility(vehicle);

        } catch (InterruptedException | IOException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Move] Erreur pour véhicule " + vehicle.getId() + " : " + e.getMessage());
        } finally {
            if (onDone != null) onDone.run();
        }
    }

    /**
     * Retourne le délai (ms) entre chaque pas selon la vitesse max du type de véhicule.
     * Référence : 110 km/h. Plus rapide = délai plus court.
     *   CAR (150 km/h)       → ~367 ms
     *   FIRE_ENGINE (110)    → 500 ms
     *   PUMPER_TRUCK (70)    → ~786 ms
     */
    private long computeStepDelay(VehicleType type) {
        if (type == null) return stepDelayMs;
        float speed = type.getMaxSpeed();
        if (speed <= 0) return stepDelayMs;
        return (long) (stepDelayMs * 110.0 / speed);
    }

    private void movement_type(VehicleDto vehicle, String teamUuid, double lon, double lat)
            throws InterruptedException, IOException {
        long vehicleDelay = computeStepDelay(vehicle.getType());

        if ("straight".equals(movementMode)) {
            moveStraightLine(vehicle.getLon(), vehicle.getLat(), lon, lat, vehicle.getId(), vehicleDelay);
        } else if ("road".equals(movementMode)) {
            moveFollower(vehicle, lon, lat, teamUuid, vehicleDelay);
        } else {
            teleport(vehicle.getId(), lon, lat);
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private void moveFollower(VehicleDto vehicle,
                              double targetLon,
                              double targetLat,
                              String teamUuid,
                              long vehicleDelay)
            throws IOException, InterruptedException {

        String url = "https://router.project-osrm.org/route/v1/driving/"
                + vehicle.getLon() + "," + vehicle.getLat()
                + ";"
                + targetLon + "," + targetLat
                + "?geometries=geojson&overview=simplified";

        System.out.println("[OSRM] Request : " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("[OSRM] HTTP Error : " + response.statusCode());
            return;
        }

        JsonNode root = objectMapper.readTree(response.body());

        if (!"Ok".equals(root.path("code").asText())) {
            System.err.println("[OSRM] Invalid response : " + root.path("code").asText());
            return;
        }

        JsonNode coordinates = root
                .path("routes").get(0)
                .path("geometry")
                .path("coordinates");

        if (coordinates == null || !coordinates.isArray()) {
            System.err.println("[OSRM] No coordinates found.");
            return;
        }

        // On saute le 1er waypoint (point de départ snapé sur la route) pour éviter
        // que le véhicule se téléporte depuis la caserne jusqu'au réseau routier.
        boolean skipFirst = true;
        for (JsonNode coord : coordinates) {
            if (skipFirst) {
                skipFirst = false;
                continue;
            }

            double lon = coord.get(0).asDouble();
            double lat = coord.get(1).asDouble();

            vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicle.getId()), new Coord(lon, lat));
            System.out.println("[Move OSRM] Vehicle " + vehicle.getId() + " -> " + lon + ", " + lat);
            Thread.sleep(vehicleDelay);
        }

        // Forcer l'arrivée aux coordonnées exactes (OSRM snape sur la route,
        // le dernier waypoint peut être légèrement décalé de la cible).
        vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicle.getId()), new Coord(targetLon, targetLat));
        Thread.sleep(vehicleDelay);

        System.out.println("[Move OSRM] Vehicle " + vehicle.getId() + " arrived at destination.");
    }

    private void teleport(Integer vehicleId, double lon, double lat) {
        vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(lon, lat));
    }

    private void moveStraightLine(double startLon, double startLat,
                                  double targetLon, double targetLat,
                                  Integer vehicleId, long vehicleDelay) throws InterruptedException {
        double currentLon = startLon;
        double currentLat = startLat;

        while (true) {
            double dLon = targetLon - currentLon;
            double dLat = targetLat - currentLat;
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);

            if (dist <= stepSize) {
                vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(targetLon, targetLat));
                break;
            }

            double ratio = stepSize / dist;
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(currentLon, currentLat));
            Thread.sleep(vehicleDelay);
        }
    }

    private void waitForFireOut(Integer fireId) throws InterruptedException {
        while (true) {
            FireDto current = fireClient.getFireById(fireId);
            if (current == null || current.getIntensity() <= 0) break;
            log.info("[Feu #{}] intensité = {}", fireId, current.getIntensity());
            Thread.sleep(fireCheckDelayMs);
        }
    }

    private void returnToFacility(VehicleDto vehicle) throws InterruptedException, IOException {
        if (vehicle.getFacilityRefID() == null) return;
        FacilityDto facility = facilityClient.getFacilityById(String.valueOf(vehicle.getFacilityRefID()));
        if (facility == null) return;
        movement_type(vehicle, teamUuid, facility.getLon(), facility.getLat());
    }
}