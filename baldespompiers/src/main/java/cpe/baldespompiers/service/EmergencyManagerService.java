
package cpe.baldespompiers.service;

import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.thread.VehicleMovementThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cerveau de l'Emergency Manager.
 *
 * Reçoit les feux/événements du EventPollerThread,
 * sélectionne les véhicules via la stratégie d'affectation,
 * et lance les déplacements via VehicleMovementThread.
 *
 * Changer @Qualifier("greedyStrategy") → @Qualifier("optimizedStrategy")
 * pour passer au Lot 3.3 sans modifier ce service.
 */
@Service
public class EmergencyManagerService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyManagerService.class);

    public enum VehicleState { MOVING, ON_FIRE }

    private final VehicleMovementThread vehicleMovementThread;
    private final Map<Integer, VehicleState> vehicleStates = new ConcurrentHashMap<>();

    @Value("${simulator.team.uuid}")
    private String teamUuid;

    public EmergencyManagerService(VehicleMovementThread vehicleMovementThread) {
        this.vehicleMovementThread = vehicleMovementThread;
    }

    public void dispatchAll(List<FireDto> fires, List<VehicleDto> vehicles) {
        List<FireDto> sortedFires = fires.stream()
                .sorted(Comparator.comparingDouble(FireDto::getIntensity).reversed())
                .toList();

        for (FireDto fire : sortedFires) {
            vehicles.stream()
                    .filter(v -> !vehicleStates.containsKey(v.getId()))
                    .filter(v -> v.getCrewMember() > 0)
                    .filter(v -> v.getLiquidQuantity() > 0)
                    .findFirst()
                    .ifPresent(vehicle -> dispatch(vehicle, fire));
        }
    }

    private void dispatch(VehicleDto vehicle, FireDto fire) {
        log.info("Dispatch véhicule {} → feu #{} (intensité={})", vehicle.getId(), fire.getId(), fire.getIntensity());
        vehicleStates.put(vehicle.getId(), VehicleState.MOVING);
        vehicleMovementThread.moveVehicle(
                vehicle,
                fire,
                teamUuid,
                () -> onArrived(vehicle.getId())
        );
    }

    public void onArrived(Integer vehicleId) {
        log.info("Véhicule {} libéré", vehicleId);
        vehicleStates.remove(vehicleId);
    }

    public Map<Integer, VehicleState> getVehicleStates() {
        return vehicleStates;
    }
}