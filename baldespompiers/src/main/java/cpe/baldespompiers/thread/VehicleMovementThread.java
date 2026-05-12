package cpe.baldespompiers.thread;

import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.VehicleDto;
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

    // "teleport" ou "straight" dans application.properties
    @Value("${movement.mode:teleport}")
    private String movementMode;

    // Taille d'un step en degrés (~200m selon latitude Lyon)
    @Value("${movement.step.size:0.002}")
    private double stepSize;

    // Délai entre deux steps en ms
    @Value("${movement.step.delay.ms:500}")
    private long stepDelayMs;

    // Délai entre deux checks d'intensité du feu en ms
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

            // ── Phase 1 : déplacement vers le feu ──────────────────────────────
            System.out.println("[Move] Véhicule " + vehicle.getId()
                    + " → feu " + fire.getId()
                    + " mode=" + movementMode);

            if ("straight".equals(movementMode)) {
                moveStraightLine(vehicle, fire.getLon(), fire.getLat(), teamUuid);
            } else {
                teleport(vehicle, fire.getLon(), fire.getLat(), teamUuid);
            }

            // ── Phase 2 : intervention — on reste sur le feu ───────────────────
            emergencyManagerService.getVehicleStates()
                    .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);

            System.out.println("[Move] Véhicule " + vehicle.getId()
                    + " arrivé sur feu " + fire.getId() + ", intervention...");

            waitForFireOut(fire.getId());

            // ── Phase 3 : retour à la caserne ──────────────────────────────────
            System.out.println("[Move] Feu " + fire.getId()
                    + " éteint, véhicule " + vehicle.getId() + " rentre.");

            returnToFacility(vehicle, teamUuid);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Move] Thread interrompu pour véhicule " + vehicle.getId());
        } finally {
            // ── Phase 4 : libération ───────────────────────────────────────────
            if (onDone != null) onDone.run();
            System.out.println("[Move] Véhicule " + vehicle.getId() + " libéré.");
        }
    }

    // ── Téléportation directe ─────────────────────────────────────────────────
    private void teleport(VehicleDto vehicle, double targetLon, double targetLat,
                          String teamUuid) {
        vehicleClient.moveVehicle(
                teamUuid,
                String.valueOf(vehicle.getId()),
                new CoordDto(targetLon, targetLat)
        );
    }

    // ── Déplacement en ligne droite pas à pas ─────────────────────────────────
    private void moveStraightLine(VehicleDto vehicle, double targetLon, double targetLat,
                                  String teamUuid) throws InterruptedException {
        double currentLon = vehicle.getLon();
        double currentLat = vehicle.getLat();

        while (true) {
            double dLon = targetLon - currentLon;
            double dLat = targetLat - currentLat;
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);

            if (dist <= stepSize) {
                // Dernier step : position exacte du feu
                vehicleClient.moveVehicle(
                        teamUuid,
                        String.valueOf(vehicle.getId()),
                        new CoordDto(targetLon, targetLat)
                );
                break;
            }

            // Avancer d'un step dans la direction du feu
            double ratio = stepSize / dist;
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            vehicleClient.moveVehicle(
                    teamUuid,
                    String.valueOf(vehicle.getId()),
                    new CoordDto(currentLon, currentLat)
            );

            Thread.sleep(stepDelayMs);
        }
    }

    // ── Attendre que le feu soit éteint ───────────────────────────────────────
    private void waitForFireOut(Integer fireId) throws InterruptedException {
        while (true) {
            FireDto current = fireClient.getFireById(fireId);

            // null = feu supprimé du simulateur = éteint
            if (current == null || current.getIntensity() <= 0) {
                break;
            }

            System.out.println("[Move] Feu " + fireId
                    + " intensité=" + current.getIntensity() + ", on continue...");

            Thread.sleep(fireCheckDelayMs);
        }
    }

    // ── Retour à la caserne d'origine ─────────────────────────────────────────
    private void returnToFacility(VehicleDto vehicle, String teamUuid)
            throws InterruptedException {

        if (vehicle.getFacilityRefID() == null) {
            System.out.println("[Move] Pas de caserne pour véhicule " + vehicle.getId());
            return;
        }

        FacilityDto facility = facilityClient.getFacilityById(vehicle.getFacilityRefID());

        if (facility == null) {
            System.out.println("[Move] Caserne introuvable pour véhicule " + vehicle.getId());
            return;
        }

        if ("straight".equals(movementMode)) {
            moveStraightLine(vehicle, facility.getLon(), facility.getLat(), teamUuid);
        } else {
            teleport(vehicle, facility.getLon(), facility.getLat(), teamUuid);
        }
    }
}
