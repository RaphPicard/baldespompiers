
package cpe.baldespompiers.service;

import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.thread.VehicleMovementThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final FireService fireService;

    private final Map<Integer, VehicleState> vehicleStates = new ConcurrentHashMap<>();
    private final Set<Integer> assignedFires = ConcurrentHashMap.newKeySet();

    /** Mode "rappel" : quand actif, aucun nouveau dispatch et les missions en cours abandonnent pour rentrer. */
    private final AtomicBoolean recallMode = new AtomicBoolean(false);

    /** IDs des véhicules à rappeler individuellement à la caserne (rappel unitaire). */ //cheker dans moveVehicule via isRecallRequested
    private final Set<Integer> recallRequestedIds = ConcurrentHashMap.newKeySet();

    public boolean isRecallMode() { return recallMode.get(); }
    public void enableRecallMode() { recallMode.set(true); log.warn("=== MODE RAPPEL ACTIVÉ ==="); }
    public void disableRecallMode() { recallMode.set(false); log.info("=== Mode rappel désactivé, dispatch repris ==="); }

    public boolean isRecallRequested(Integer vehicleId) {
        return recallMode.get() || recallRequestedIds.contains(vehicleId); // recallMode mis à True par VehicleRestcrt appelé par l'appui du boutton en html
    }
    public void requestRecall(Integer vehicleId) { recallRequestedIds.add(vehicleId); log.info("Rappel individuel demandé : véhicule {}", vehicleId); }
    public void clearRecallRequest(Integer vehicleId) { recallRequestedIds.remove(vehicleId); log.info("Rappel individuel annulé : véhicule {}", vehicleId); }
    public boolean isVehicleInMission(Integer vehicleId) { return vehicleStates.containsKey(vehicleId); }
    public Set<Integer> getRecallRequestedIds() { return recallRequestedIds; }

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    public EmergencyManagerService(VehicleMovementThread vehicleMovementThread,
                                   @Lazy FireService fireService) {
        this.vehicleMovementThread = vehicleMovementThread;
        this.fireService = fireService;
    }

    // ── Dispatch ───────────────────────────────────────────────────────────────

    public void dispatchAll(List<FireDto> fires, List<VehicleDto> vehicles) {
        if (recallMode.get()) { log.debug("dispatchAll skip (mode rappel)"); return; }
        fireService.dispatchFires(fires, vehicles);
    }

    public void dispatch(VehicleDto vehicle, FireDto fire) {
        log.info("Dispatch véhicule {} → feu #{} (intensité={})", vehicle.getId(), fire.getId(), fire.getIntensity());
        vehicleStates.put(vehicle.getId(), VehicleState.MOVING); // réserve le véhicule : il ne sera plus proposé à d'autres feux
        assignedFires.add(fire.getId());                          // réserve le feu : aucun autre véhicule ne sera dispatchés dessus
        // Lance le déplacement dans un thread dédié (@Async) ; onArrived sera appelé à la fin de la mission
        vehicleMovementThread.moveVehicle(
                vehicle,
                fire,
                teamUuid,
                () -> onArrived(vehicle.getId(), fire.getId())
        );
    }

    // Appelée à la fin de chaque mission (après retour caserne + recharge), libère le véhicule pour un nouveau dispatch
    public void onArrived(Integer vehicleId, Integer fireId) {
        log.info("Véhicule {} libéré", vehicleId);
        vehicleStates.remove(vehicleId); // le véhicule est à nouveau disponible
        assignedFires.remove(fireId);    // le feu est retiré des assignations (éteint ou abandonné)
        recallRequestedIds.remove(vehicleId); // efface un éventuel rappel individuel en attente
    }

    public Map<Integer, VehicleState> getVehicleStates() {
        return vehicleStates;
    }

    public Set<Integer> getAssignedFires() {
        return assignedFires;
    }
}