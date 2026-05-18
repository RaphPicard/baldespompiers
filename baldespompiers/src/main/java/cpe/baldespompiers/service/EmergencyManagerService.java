
package cpe.baldespompiers.service;

import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.model.type.LiquidType;
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

    // ── Compatibilité liquide ──────────────────────────────────────────────────

    /** Vrai si le liquide du véhicule est efficace contre ce type de feu (efficacité > 10 %). */
    private boolean isLiquidCompatible(LiquidType liquid, String fireType) {
        if (liquid == null || fireType == null) return false;
        return liquid.getEfficiency(fireType) > 0.1;
    }

    // ── Score d'aptitude ───────────────────────────────────────────────────────

    /**
     * Score d'aptitude d'un véhicule pour un feu donné.
     * Pondération : compatibilité liquide (×50) > équipage (×10) > liquide + carburant.
     * Un véhicule parfaitement compatible (efficiency=1.0) gagne +50, ce qui prime sur
     * les petites différences de ressources, mais pas sur la taille de l'équipage.
     */
    private double vehicleScore(VehicleDto v, FireDto fire) {
        double efficiency = (v.getLiquidType() != null)
                ? v.getLiquidType().getEfficiency(fire.getType())
                : 0.0;
        return efficiency * 50.0 + v.getCrewMember() * 10.0 + v.getLiquidQuantity() + v.getFuel();
    }

    // ── Filtres de candidats ───────────────────────────────────────────────────

    /** Véhicules libres, au-dessus des seuils minimaux et compatibles avec le type de feu. */
    private Stream<VehicleDto> candidates(List<VehicleDto> vehicles, FireDto fire) {
        return vehicles.stream()
                .filter(v -> !vehicleStates.containsKey(v.getId()))
                .filter(v -> isLiquidCompatible(v.getLiquidType(), fire.getType()))
                .filter(v -> v.getCrewMember() >= minCrew)
                .filter(v -> v.getFuel() >= minFuel)
                .filter(v -> v.getLiquidQuantity() >= minLiquid);
    }

    /** Véhicules "prêts" : candidats valides avec ressources au-dessus des seuils préférés. */
    private Stream<VehicleDto> best_candidates(List<VehicleDto> vehicles, FireDto fire) {
        return candidates(vehicles, fire)
                .filter(v -> v.getFuel() >= readyFuel)
                .filter(v -> v.getLiquidQuantity() >= readyLiquid);
    }

    // ── Dispatch ───────────────────────────────────────────────────────────────

    public void dispatchAll(List<FireDto> fires, List<VehicleDto> vehicles) {
        List<FireDto> sortedFires = fires.stream()
                .sorted(Comparator.comparingDouble(FireDto::getIntensity).reversed())
                .toList();

        for (FireDto fire : sortedFires) {
            if (assignedFires.contains(fire.getId())) continue;

            // Tier 1 : véhicule "prêt" et compatible → meilleur score
            Optional<VehicleDto> ready = best_candidates(vehicles, fire)
                    .max(Comparator.comparingDouble(v -> vehicleScore(v, fire)));

            if (ready.isPresent()) {
                dispatch(ready.get(), fire);
                continue;
            }

            // Tier 2 : aucun véhicule "prêt" → fallback sur tout candidat compatible au-dessus du minimum
            candidates(vehicles, fire)
                    .max(Comparator.comparingDouble(v -> vehicleScore(v, fire)))
                    .ifPresentOrElse(
                            vehicle -> {
                                log.warn("Feu #{} — aucun véhicule prêt (fuel≥{}/liq≥{}), dispatch avec ressources partielles : véhicule {} (fuel={}, liq={})",
                                        fire.getId(), readyFuel, readyLiquid,
                                        vehicle.getId(), vehicle.getFuel(), vehicle.getLiquidQuantity());
                                dispatch(vehicle, fire);
                            },
                            () -> log.warn("Feu #{} — aucun véhicule disponible (tous occupés, sous le seuil minimum ou liquide incompatible)", fire.getId())
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
