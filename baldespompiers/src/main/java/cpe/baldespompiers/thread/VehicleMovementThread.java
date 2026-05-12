package cpe.baldespompiers.thread;

import cpe.baldespompiers.client.FacilityClient;
import cpe.baldespompiers.client.FireClient;
import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.service.EmergencyManagerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;


/**
 * Déplacement progressif d'un véhicule via @Async.
 * Un thread par véhicule, exécuté dans vehicleMovementExecutor.
 *
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
            if ("straight".equals(movementMode)) {
                moveStraightLine(vehicle.getLon(), vehicle.getLat(),
                        fire.getLon(), fire.getLat(),
                        vehicle.getId());
            } else {
                teleport(vehicle.getId(), fire.getLon(), fire.getLat());
            }

            // Phase 2 : on est sur le feu, on attend qu'il soit éteint
            emergencyManagerService.getVehicleStates()
                    .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);

            waitForFireOut(fire.getId());

            // Phase 3 : retour à la caserne
            returnToFacility(vehicle);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Phase 4 : libération dans tous les cas
            if (onDone != null) onDone.run();
        }
    }

    // ── Téléportation ─────────────────────────────────────────────────────────
    private void teleport(Integer vehicleId, double lon, double lat) {
        vehicleClient.moveVehicle(
                teamUuid,
                String.valueOf(vehicleId),
                new Coord(lon, lat)
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
                        new Coord(targetLon, targetLat));
                break;
            }

            // Avancer d'un step vers la cible
            double ratio = stepSize / dist;
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                    new Coord(currentLon, currentLat));

            Thread.sleep(stepDelayMs);
        }
    }

    // ── Attendre que le feu soit éteint ───────────────────────────────────────
    private void waitForFireOut(Integer fireId) throws InterruptedException {
        while (true) {
            FireDto current = fireClient.getFireById(fireId);
            if (current == null || current.getIntensity() <= 0) break;
            Thread.sleep(fireCheckDelayMs);
        }
    }

    // ── Retour caserne ────────────────────────────────────────────────────────
    private void returnToFacility(VehicleDto vehicle) throws InterruptedException {
        if (vehicle.getFacilityRefID() == null) return;

        FacilityDto facility = facilityClient.getFacilityById(String.valueOf(vehicle.getFacilityRefID()));
        if (facility == null) return;

        if ("straight".equals(movementMode)) {
            moveStraightLine(vehicle.getLon(), vehicle.getLat(),
                    facility.getLon(), facility.getLat(),
                    vehicle.getId());
        } else {
            teleport(vehicle.getId(), facility.getLon(), facility.getLat());
        }
    }
}
