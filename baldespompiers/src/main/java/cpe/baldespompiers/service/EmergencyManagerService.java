
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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
    private final Set<Integer> assignedFires = ConcurrentHashMap.newKeySet();

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    // Seuil absolu : en dessous, le véhicule est exclu du dispatch
    @Value("${dispatch.min.fuel:5.0}")
    private float minFuel;

    @Value("${dispatch.min.liquid:5.0}")
    private float minLiquid;

    @Value("${dispatch.min.crew:1}")
    private int minCrew;

    // Seuil "prêt" : on préfère envoyer un véhicule au-dessus de ces valeurs.
    // Si aucun n'est disponible, on accepte tout véhicule > seuil minimum.
    @Value("${dispatch.ready.fuel:40.0}")
    private float readyFuel;

    @Value("${dispatch.ready.liquid:40.0}")
    private float readyLiquid;

    public EmergencyManagerService(VehicleMovementThread vehicleMovementThread) {
        this.vehicleMovementThread = vehicleMovementThread;
    }

    /** Score d'aptitude : préférer les véhicules les mieux ravitaillés et les plus dotés en personnel. */
    private double vehicleScore(VehicleDto v) {
        return v.getCrewMember() * 10.0 + v.getLiquidQuantity() + v.getFuel();
    }

    /** Véhicules libres et au-dessus des seuils minimaux. */
    private Stream<VehicleDto> candidates(List<VehicleDto> vehicles) {
        return vehicles.stream()
                .filter(v -> !vehicleStates.containsKey(v.getId()))
                .filter(v -> v.getCrewMember() >= minCrew)
                .filter(v -> v.getFuel() >= minFuel)
                .filter(v -> v.getLiquidQuantity() >= minLiquid);
    }

    /** Véhicules "prêts" : au-dessus des seuils préférés, aptes à une mission complète. */
    private Stream<VehicleDto> best_candidates(List<VehicleDto> vehicles) {
        return candidates(vehicles)
                .filter(v -> v.getFuel() >= readyFuel)
                .filter(v -> v.getLiquidQuantity() >= readyLiquid);
    }





    public void dispatchAll(List<FireDto> fires, List<VehicleDto> vehicles) {
        List<FireDto> sortedFires = fires.stream()
                .sorted(Comparator.comparingDouble(FireDto::getIntensity).reversed())
                .toList();

        for (FireDto fire : sortedFires) {
            if (assignedFires.contains(fire.getId())) continue;

            // Tier 1 : véhicule "prêt" (il a des ressources suffisantes pour une mission de manière efficace)
            Optional<VehicleDto> ready = best_candidates(vehicles)
                    .max(Comparator.comparingDouble(this::vehicleScore));

            if (ready.isPresent()) {
                dispatch(ready.get(), fire); // .get() pour car ready est une
                continue;   // pour le second tier
            }

            // Tier 2 : aucun véhicule "prêt" → on accepte le meilleur au-dessus du minimum
            // pour ne pas laisser le feu s'étendre pendant que les véhicules se rechargent
            candidates(vehicles)
                    .max(Comparator.comparingDouble(this::vehicleScore))
                    .ifPresentOrElse(
                            vehicle -> {
                                log.warn("Feu #{} — aucun véhicule prêt (fuel≥{}/liq≥{}), dispatch avec ressources partielles : véhicule {} (fuel={}, liq={})",
                                        fire.getId(), readyFuel, readyLiquid,
                                        vehicle.getId(), vehicle.getFuel(), vehicle.getLiquidQuantity());
                                dispatch(vehicle, fire);
                            },
                            () -> log.warn("Feu #{} — aucun véhicule disponible (tous occupés ou sous le seuil minimum)", fire.getId())
                    );
        }
    }

    public void dispatch(VehicleDto vehicle, FireDto fire) {
        log.info("Dispatch véhicule {} → feu #{} (intensité={})", vehicle.getId(), fire.getId(), fire.getIntensity());
        vehicleStates.put(vehicle.getId(), VehicleState.MOVING);
        assignedFires.add(fire.getId());
        vehicleMovementThread.moveVehicle(
                vehicle,
                fire,
                teamUuid,
                () -> onArrived(vehicle.getId(), fire.getId())
        );
    }

    public void onArrived(Integer vehicleId, Integer fireId) {
        log.info("Véhicule {} libéré", vehicleId);
        vehicleStates.remove(vehicleId);
        assignedFires.remove(fireId);
    }

    public Map<Integer, VehicleState> getVehicleStates() {
        return vehicleStates;
    }
}