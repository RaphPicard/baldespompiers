package cpe.baldespompiers.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.model.dto.VehicleDto;
import cpe.baldespompiers.client.FacilityClient;
import cpe.baldespompiers.client.FireClient;
import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.service.EmergencyManagerService;
import org.springframework.beans.factory.annotation.Value;
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

 * Modes (movement.mode dans application.properties) :
 *   "teleport" → PUT direct sur destination finale
 *   "straight" → interpolation ligne droite (+50 pts, ×1)
 *   "road"     → waypoints OSRM               (+50 pts, ×2 ou ×3 avec vitesse)
 */

@Component
public class VehicleMovementThread {

    private final VehicleClient vehicleClient;
    private final FireClient fireClient;
    private final FacilityClient facilityClient;
    private final EmergencyManagerService emergencyManagerService;

    @Value("${simulator.team.uuid}")
    private String teamUuid;

    @Value("${movement.mode:teleport}")
    private String movementMode;

    @Value("${movement.step.size:0.002}")
    private double stepSize;

    @Value("${movement.step.delay.ms:500}")
    private long stepDelayMs;

    @Value("${movement.fire.check.delay.ms:3000}")
    private long fireCheckDelayMs;

    public VehicleMovementThread(VehicleClient vehicleClient,
                                 FireClient fireClient,
                                 FacilityClient facilityClient,
                                 EmergencyManagerService emergencyManagerService) {
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

            // Phase 2 : on est sur le feu, on attend qu'il soit éteint
            emergencyManagerService.getVehicleStates()
                    .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);

            waitForFireOut(fire.getId());

            // Phase 3 : retour à la caserne
            returnToFacility(vehicle);

        } catch (InterruptedException | IOException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("[Move] Erreur OSRM pour véhicule " + vehicle.getId() + " : " + e.getMessage());
        } finally {
            // Phase 4 : libération dans tous les cas
            if (onDone != null) onDone.run();
        }
    }

    private void movement_type(VehicleDto vehicle, String teamUuid, double lon, double lat) throws InterruptedException, IOException {
        if ("straight".equals(movementMode)) {

            moveStraightLine(
                    vehicle.getLon(),
                    vehicle.getLat(),
                    lon,
                    lat,
                    vehicle.getId()
            );

        } else if ("road".equals(movementMode)) {

            moveFollower(
                    vehicle,
                    lon,
                    lat,
                    teamUuid
            );

        } else {

            teleport(vehicle.getId(),
                    lon,
                    lat);
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
                              String teamUuid)
            throws IOException, InterruptedException {

        // Construction URL OSRM
        String url_ending = "?geometries=geojson&overview=simplified";
        String url_beginning = "https://router.project-osrm.org/route/v1/driving/";
        String url = url_beginning
                + vehicle.getLon() + "," + vehicle.getLat()
                + ";"
                + targetLon + "," + targetLat
                + url_ending;

        System.out.println("[OSRM] Request : " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Vérification HTTP
        if (response.statusCode() != 200) {
            System.err.println("[OSRM] HTTP Error : " + response.statusCode());
            return;
        }

        // Parse JSON
        JsonNode root = objectMapper.readTree(response.body());

        // Vérification réponse OSRM
        if (!"Ok".equals(root.path("code").asText())) {
            System.err.println("[OSRM] Invalid response : "
                    + root.path("code").asText());
            return;
        }

        JsonNode coordinates = root
                .path("routes")
                .get(0)
                .path("geometry")
                .path("coordinates");

        if (coordinates == null || !coordinates.isArray()) {
            System.err.println("[OSRM] No coordinates found.");
            return;
        }

        // suivi de route waypoint par waypoint
        for (JsonNode coord : coordinates) {

            double lon = coord.get(0).asDouble();
            double lat = coord.get(1).asDouble();

            vehicleClient.moveVehicle(
                    teamUuid,
                    String.valueOf(vehicle.getId()),
                    new cpe.baldespompiers.model.dto.Coord(lon, lat)
            );

            // Debug console
            System.out.println("[Move] Vehicle "
                    + vehicle.getId()
                    + " -> "
                    + lon + ", "
                    + lat);

            Thread.sleep(stepDelayMs);
        }

        System.out.println("[Move] Vehicle "
                + vehicle.getId()
                + " arrived at destination.");
    }

    // ── Téléportation ─────────────────────────────────────────────────────────
    private void teleport(Integer vehicleId, double lon, double lat) {
        vehicleClient.moveVehicle(
                teamUuid,
                String.valueOf(vehicleId),
                new cpe.baldespompiers.model.dto.Coord(lon, lat)
        );
    }

    // ── Ligne droite simple ───────────────────────────────────────────────────
    private void moveStraightLine(double startLon, double startLat,
                                  double targetLon, double targetLat,
                                  Integer vehicleId) throws InterruptedException {
        double currentLon = startLon;
        double currentLat = startLat;

        while (true) {
            double dLon = targetLon - currentLon;
            double dLat = targetLat - currentLat;
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);

            // Arrivé à destination
            if (dist <= stepSize) {
                vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                        new cpe.baldespompiers.model.dto.Coord(targetLon, targetLat));
                break;
            }

            // Avancer d'un step vers la cible
            double ratio = stepSize / dist;
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(currentLon, currentLat));

            Thread.sleep(stepDelayMs);
        }
    }

    // ── Attendre que le feu soit éteint ───────────────────────────────────────
    private void waitForFireOut(Integer fireId) throws InterruptedException {
        while (true) {
            cpe.baldespompiers.model.dto.FireDto current = fireClient.getFireById(fireId);
            if (current == null || current.getIntensity() <= 0) break;
            Thread.sleep(fireCheckDelayMs);
        }
    }

    // ── Retour caserne ────────────────────────────────────────────────────────
    private void returnToFacility(VehicleDto vehicle) throws InterruptedException, IOException {
        if (vehicle.getFacilityRefID() == null) return;

        FacilityDto facility = facilityClient.getFacilityById(String.valueOf(vehicle.getFacilityRefID()));
        if (facility == null) return;

        movement_type(vehicle, teamUuid, facility.getLon(), facility.getLat());
    }
}