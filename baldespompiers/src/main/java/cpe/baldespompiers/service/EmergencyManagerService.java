package cpe.baldespompiers.service;

import cpe.baldespompiers.model.dto.EmergencyEventDto;
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

@Service
public class EmergencyManagerService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyManagerService.class);

    public enum VehicleState { MOVING, ON_FIRE }

    private final VehicleMovementThread vehicleMovementThread;
    private final FireService fireService;
    private final RPEventService rpEventService;

    private final Map<Integer, VehicleState> vehicleStates  = new ConcurrentHashMap<>();
    private final Set<Integer> assignedFires                 = ConcurrentHashMap.newKeySet();
    private final Set<Integer> assignedEvents                = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean recallMode       = new AtomicBoolean(false);
    private final Set<Integer> recallRequestedIds = ConcurrentHashMap.newKeySet();

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    public EmergencyManagerService(VehicleMovementThread vehicleMovementThread,
                                   @Lazy FireService fireService,
                                   @Lazy RPEventService rpEventService) {
        this.vehicleMovementThread = vehicleMovementThread;
        this.fireService           = fireService;
        this.rpEventService        = rpEventService;
    }

    // ── Recall ────────────────────────────────────────────────────────────────
    /** IDs des véhicules à rappeler individuellement à la caserne (rappel unitaire). */ //cheker dans moveVehicule via isRecallRequested

    public boolean isRecallMode() { return recallMode.get(); }
    public void enableRecallMode() { recallMode.set(true); log.warn("=== MODE RAPPEL ACTIVÉ ==="); }
    public void disableRecallMode() { recallMode.set(false); log.info("=== Mode rappel désactivé, dispatch repris ==="); }

    public boolean isRecallRequested(Integer vehicleId) {
        return recallMode.get() || recallRequestedIds.contains(vehicleId);
    }
    public void requestRecall(Integer vehicleId) { recallRequestedIds.add(vehicleId); log.info("Rappel individuel demandé : véhicule {}", vehicleId); }
    public void clearRecallRequest(Integer vehicleId) { recallRequestedIds.remove(vehicleId); log.info("Rappel individuel annulé : véhicule {}", vehicleId); }
    public boolean isVehicleInMission(Integer vehicleId) { return vehicleStates.containsKey(vehicleId); }
    public Set<Integer> getRecallRequestedIds() { return recallRequestedIds; }

    // ── Dispatch feux ─────────────────────────────────────────────────────────
    public void dispatchAll(List<FireDto> fires, List<VehicleDto> vehicles) {
        if (recallMode.get()) { log.debug("dispatchAll skip (mode rappel)"); return; }
        fireService.dispatchFires(fires, vehicles);
    }

    public void dispatch(VehicleDto vehicle, FireDto fire) {
        log.info("Dispatch véhicule {} → feu #{} (intensité={})",
                vehicle.getId(), fire.getId(), fire.getIntensity());
        vehicleStates.put(vehicle.getId(), VehicleState.MOVING);
        assignedFires.add(fire.getId());
        vehicleMovementThread.moveVehicle(
                vehicle, fire, teamUuid,
                () -> onArrived(vehicle.getId(), fire.getId())
        );
    }

    public void onArrived(Integer vehicleId, Integer fireId) {
        log.info("Véhicule {} libéré (feu {})", vehicleId, fireId);
        vehicleStates.remove(vehicleId);
        assignedFires.remove(fireId);
        recallRequestedIds.remove(vehicleId);
    }

    // ── Dispatch events ───────────────────────────────────────────────────────
    public void dispatchAllEvents(List<EmergencyEventDto> events, List<VehicleDto> vehicles) {
        if (recallMode.get()) return;
        rpEventService.dispatchEvents(events, vehicles);
    }

    public void dispatchEvent(VehicleDto vehicle, EmergencyEventDto event) {
        log.info("Dispatch véhicule {} → event #{} (type={})",
                vehicle.getId(), event.getId(), event.getEventType());
        vehicleStates.put(vehicle.getId(), VehicleState.MOVING);
        assignedEvents.add(event.getId());
        vehicleMovementThread.moveVehicleToEvent(
                vehicle, event, teamUuid,
                () -> onEventArrived(vehicle.getId(), event.getId())
        );
    }

    public void onEventArrived(Integer vehicleId, Integer eventId) {
        log.info("Véhicule {} libéré (event {})", vehicleId, eventId);
        vehicleStates.remove(vehicleId);
        assignedEvents.remove(eventId);
        recallRequestedIds.remove(vehicleId);
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public Map<Integer, VehicleState> getVehicleStates() { return vehicleStates; }
    public Set<Integer> getAssignedFires()               { return assignedFires; }
    public Set<Integer> getAssignedEvents()              { return assignedEvents; }
}