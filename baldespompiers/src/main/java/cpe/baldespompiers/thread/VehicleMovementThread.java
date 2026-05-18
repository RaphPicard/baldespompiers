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
import java.util.ArrayList;
import java.util.List;

/**
 * Déplacement progressif d'un véhicule via @Async.
 * Un thread par véhicule, exécuté dans vehicleMovementExecutor.
 *
 * Modes (movement.mode dans application.properties) :
 *   "teleport" → PUT direct sur destination finale
 *   "straight" → interpolation ligne droite pas à pas
 *   "road"     → waypoints OSRM (géométrie complète) + interpolation entre chaque segment
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

    /** Taille d'un pas en degrés (~0.0005° ≈ 55 m, ~0.002° ≈ 220 m). */
    @Value("${movement.step.size:0.0005}")
    private double stepSize;

    /** Délai de base entre deux pas (ms) — référence pour un véhicule à 110 km/h. */
    @Value("${movement.step.delay.ms:300}")
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

    // ── Point d'entrée principal ───────────────────────────────────────────────

    @Async("vehicleMovementExecutor")
    public void moveVehicle(VehicleDto vehicle, FireDto fire, String teamUuid, Runnable onDone) {
        try {
            // Phase 1 : aller au feu
            movement_type(vehicle, teamUuid, fire.getLon(), fire.getLat());
            // Mise à jour de la position pour que le trajet retour parte du bon endroit
            vehicle.setLon(fire.getLon());
            vehicle.setLat(fire.getLat());

            // Phase 2 : attente extinction du feu
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
     * Déplacement progressif vers une coordonnée arbitraire (sans logique feu/caserne).
     * Utilisé par /api/vehicles/{id}/move pour respecter la vitesse max du simulateur.
     */
    @Async("vehicleMovementExecutor")
    public void moveTo(VehicleDto vehicle, double lon, double lat) {
        try {
            movement_type(vehicle, teamUuid, lon, lat);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Déplacement interrompu pour véhicule {}", vehicle.getId());
        } catch (IOException e) {
            log.error("Erreur déplacement véhicule {} : {}", vehicle.getId(), e.getMessage());
        }
    }

    // ── Sélection du mode de déplacement ──────────────────────────────────────

    private void movement_type(VehicleDto vehicle, String teamUuid, double lon, double lat)
            throws InterruptedException, IOException {
        long vehicleDelay = computeStepDelay(vehicle.getType());

        if ("straight".equals(movementMode)) {
            // Ligne droite : un seul appel moveToPoint pour tout le trajet
            moveToPoint(vehicle.getLon(), vehicle.getLat(), lon, lat, vehicle.getId(), vehicleDelay);

        } else if ("road".equals(movementMode)) {
            // Route réelle : OSRM + interpolation segment par segment
            moveFollower(vehicle, lon, lat, teamUuid, vehicleDelay);

        } else {
            // Téléportation (défaut)
            teleport(vehicle.getId(), lon, lat);
        }
    }

    /**
     * Calcule le délai (ms) entre deux pas selon la vitesse max du type de véhicule.
     * Référence : 110 km/h → stepDelayMs.
     * Un véhicule plus rapide attend moins longtemps, un plus lent attend plus.
     *
     *   CAR              (150 km/h) → stepDelayMs × 110/150 ≈ ×0.73  (plus rapide)
     *   FIRE_ENGINE      (110 km/h) → stepDelayMs × 1.00              (référence)
     *   PUMPER_TRUCK     ( 70 km/h) → stepDelayMs × 110/70  ≈ ×1.57  (plus lent)
     *   EMERGENCY_AMBULANCE (110)   → stepDelayMs × 1.00
     */
    private long computeStepDelay(VehicleType type) {
        if (type == null) return stepDelayMs;
        float speed = type.getMaxSpeed();
        if (speed <= 0) return stepDelayMs;
        return (long) (stepDelayMs * 110.0 / speed);
    }

    // ── Mode route (OSRM) ─────────────────────────────────────────────────────

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Récupère l'itinéraire routier réel via OSRM (overview=full → géométrie complète),
     * puis déplace le véhicule segment par segment avec interpolation fine.
     *
     * overview=full donne tous les points de la route (virages, carrefours…),
     * contrairement à overview=simplified qui en renvoie très peu.
     * Entre chaque paire de waypoints OSRM consécutifs, moveToPoint insère
     * des positions intermédiaires espacées de stepSize pour un mouvement fluide.
     */
    private void moveFollower(VehicleDto vehicle,
                              double targetLon, double targetLat,
                              String teamUuid, long vehicleDelay)
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

        // Conversion du tableau JSON en liste pour accès par index
        List<double[]> waypoints = new ArrayList<>();
        for (JsonNode coord : coordinates) {
            waypoints.add(new double[]{ coord.get(0).asDouble(), coord.get(1).asDouble() });
        }

        System.out.println("[OSRM] " + waypoints.size() + " waypoints reçus pour véhicule " + vehicle.getId());

        // On part de l'index 0 (point de départ snapé sur la route) sans l'envoyer
        // au simulateur — évite la téléportation initiale.
        // Il sert uniquement de point "from" pour la première interpolation.
        for (int i = 1; i < waypoints.size(); i++) {
            double[] from = waypoints.get(i - 1);
            double[] to   = waypoints.get(i);
            moveToPoint(from[0], from[1], to[0], to[1], vehicle.getId(), vehicleDelay);
        }

        // Position exacte finale : OSRM snape sur la route, le dernier waypoint
        // peut être légèrement décalé de la cible réelle (ex : feu en pleine forêt).
        vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicle.getId()),
                new Coord(targetLon, targetLat));
        Thread.sleep(vehicleDelay);

        System.out.println("[Move OSRM] Vehicle " + vehicle.getId() + " arrived at destination.");
    }

    // ── Téléportation ─────────────────────────────────────────────────────────

    private void teleport(Integer vehicleId, double lon, double lat) {
        vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(lon, lat));
    }

    // ── Interpolation pas à pas (partagée par straight et road) ───────────────

    /**
     * Déplace le véhicule pas à pas de (fromLon, fromLat) vers (toLon, toLat).
     *
     * Utilisée dans deux contextes :
     *   - mode "straight" : appelée une fois pour tout le trajet (départ → destination)
     *   - mode "road"     : appelée pour chaque segment entre deux waypoints OSRM consécutifs,
     *                       produisant une interpolation fine qui suit la géométrie de la route
     *
     * À chaque itération, on avance de stepSize degrés dans la direction de la cible.
     * Quand la distance restante est ≤ stepSize, on se pose exactement sur la cible.
     */
    private void moveToPoint(double fromLon, double fromLat,
                             double toLon, double toLat,
                             Integer vehicleId, long vehicleDelay) throws InterruptedException {
        double currentLon = fromLon;
        double currentLat = fromLat;

        while (true) {
            double dLon = toLon - currentLon;
            double dLat = toLat - currentLat;
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);

            if (dist <= stepSize) {
                // Dernier pas : on se pose exactement sur la cible du segment
                vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                        new Coord(toLon, toLat));
                Thread.sleep(vehicleDelay);
                break;
            }

            // Avancer d'exactement stepSize dans la direction de la cible
            double ratio = stepSize / dist;
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                    new Coord(currentLon, currentLat));
            Thread.sleep(vehicleDelay);
        }
    }

    // ── Attente extinction du feu ─────────────────────────────────────────────

    private void waitForFireOut(Integer fireId) throws InterruptedException {
        while (true) {
            FireDto current = fireClient.getFireById(fireId);
            if (current == null || current.getIntensity() <= 0) break;
            log.info("[Feu #{}] intensité = {}", fireId, current.getIntensity());
            Thread.sleep(fireCheckDelayMs);
        }
    }

    // ── Retour à la caserne ───────────────────────────────────────────────────

    private void returnToFacility(VehicleDto vehicle) throws InterruptedException, IOException {
        if (vehicle.getFacilityRefID() == null) return;
        FacilityDto facility = facilityClient.getFacilityById(String.valueOf(vehicle.getFacilityRefID()));
        if (facility == null) return;
        movement_type(vehicle, teamUuid, facility.getLon(), facility.getLat());
    }
}