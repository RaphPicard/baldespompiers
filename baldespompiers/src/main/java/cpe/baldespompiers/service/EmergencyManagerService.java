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
    private final Set<Integer> repositioningVehicles         = ConcurrentHashMap.newKeySet(); // les véhicules qui se positionnent aux centroïdes de la zone de la map

    private final AtomicBoolean recallMode       = new AtomicBoolean(false); // si true alors tous les véhicules en mission doivent être rappelés à la caserne (rappel global), sinon seuls les véhicules dont l'ID est dans recallRequestedIds doivent être rappelés (rappel unitaire)
    private final Set<Integer> recallRequestedIds = ConcurrentHashMap.newKeySet(); // sera vérifier dans moveVehicle pour savoir si un véhicule doit être rappelé à la caserne (rappel unitaire)

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    public EmergencyManagerService(VehicleMovementThread vehicleMovementThread,
                                   @Lazy FireService fireService, //@Lazy pour éviter la dépendance circulaire (EmergencyManagerService → FireService → EventPollerThread → EmergencyManagerService)
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


    // ── Repositionnement ──────────────────────────────────────────────────────
    public boolean isRepositioning(Integer vehicleId) { return repositioningVehicles.contains(vehicleId); }
    public void stopRepositioning(Integer vehicleId)  { repositioningVehicles.remove(vehicleId); }

    public void repositionVehicle(VehicleDto vehicle, double lon, double lat) {
        repositioningVehicles.add(vehicle.getId()); // on l'ajoute à la liste des véhicules en repositionnement (pour que le thread de mouvement puisse vérifier s'il doit continuer à le faire ou pas)
        log.debug("Véhicule chargé complètement {} → repositionnement vers (le centroïde?) ({}, {})", vehicle.getId(), lon, lat);
        vehicleMovementThread.repositionVehicle(vehicle, lon, lat);
    }


    // ── Dispatch feux ─────────────────────────────────────────────────────────
    public void dispatchAllFires(List<FireDto> fires, List<VehicleDto> vehicles) {
        if (recallMode.get()) { log.debug("dispatchAll skip (mode rappel)"); return; } // guard pour le bouton de rappel global
        fireService.dispatchFires(fires, vehicles); // ca va FILTRER et TRIER les feux PUIS appeler dispatch d'en dessous
        // dispatchAllFires va appeler dispatch qui va assigner les vehiclesStates et après repositionIdleVehicle pourra les chek
        if (fires != null && !fires.isEmpty()) repositionIdleVehicles(vehicles); // les vehicules inactifs vont renter se charger au MAX et se postionner aux centroidess
    }

    // |
    // |
    // v

    public void dispatch(VehicleDto vehicle, FireDto fire) { // appelée dans FireService
        log.info("Dispatch véhicule {} → feu #{} (intensité={})",
                vehicle.getId(), fire.getId(), fire.getIntensity());
        log.info("Véhicule {} repositionnement vers centroïde annulé)", vehicle.getId());
        repositioningVehicles.remove(vehicle.getId()); // annule le repositionnement si en cours → le thread TO_REPOSITION s'arrêtera à son prochain pas

        vehicleStates.put(vehicle.getId(), VehicleState.MOVING); // pour indiquer que ce véhicule est en mission (en train de se déplacer vers un feu)
        assignedFires.add(fire.getId()); // pour ne pas que le même feu soit réassigné à un autre véhicule tant que le premier n'est pas arrivé (éviter les doublons)
        vehicleMovementThread.moveVehicle(
                vehicle, fire, teamUuid,    // si jamais le teamUuid ne sert à rien ici on peut l'enlever, et de move vehicle aussi et ... ???
                () -> onArrived(vehicle.getId(), fire.getId())
        );
    }
    //dispatch() appelle vehicleMovementThread.moveVehicle() qui est @Async — il démarre un thread et retourne immédiatement (quelques microsecondes plus tard)
    // onArrived() est appelé à la toute fin de ce thread async, après returnToFacility + waitForRecharge + extinction du feu, soit des secondes/minutes plus tard.

    //   repositionIdleVehicles() s'exécute dans le même thread synchrone que dispatchAllFires(), quelques microsecondes après dispatchFires()
    private void repositionIdleVehicles(List<VehicleDto> vehicles) {
        if (vehicles == null || vehicles.isEmpty()) return;
        vehicles.stream()
                .filter(v -> !vehicleStates.containsKey(v.getId())) // on ne garde que les véhicules inactifs
                .filter(v -> repositioningVehicles.add(v.getId())) //  skip si déjà présent, sinon marque et continue
                .forEach(v -> {
                    log.debug("Véhicule {} inactif → caserne + recharge 100% + repositionement aux centroïde", v.getId());
                    vehicleMovementThread.recallIdleVehicle(v, null); // appel returnToFacility + Recharge + RepositionToCentroid  (le null c'est pour le callback à la fin du repositionnement,
                    // mais comme c'est un repositionnement "classique" vers le centroïde on n'en a pas besoin, contrairement au repositionnement vers un feu ou un event où on doit libérer le véhicule à la fin du déplacement)
                });
    }

    public void onArrived(Integer vehicleId, Integer fireId) {
        log.info("Véhicule {} libéré (feu {})", vehicleId, fireId);
        vehicleStates.remove(vehicleId); // retire lui (clé) et son state (valeur) de la map des véhicules en mission
        assignedFires.remove(fireId);

        recallRequestedIds.remove(vehicleId); // si jamais ce véhicule avait été demandé à être rappelé individuellement, on annule cette demande (car il est arrivé à destination et n'est plus en mission) => éviter que le véhicule soit rappelé à la caserne alors qu'il vient d'arriver sur un feu
    }

    // pour les events c'est vraiment du COPIER COLLER du code pour le feu ci dessus, à factoriser ?
    // ── Dispatch events ───────────────────────────────────────────────────────
    public void dispatchAllEvents(List<EmergencyEventDto> events, List<VehicleDto> vehicles) {
        if (recallMode.get()) return; // guard pour le bouton de rappel global
        rpEventService.dispatchEvents(events, vehicles); // ca va FILTRER et TRIER les events PUIS appeler dispatchEvent d'en dessous
    }
    
    // |
    // |
    // v

    public void dispatchEvent(VehicleDto vehicle, EmergencyEventDto event) {
        log.info("Dispatch véhicule {} → event #{} (type={})",
                vehicle.getId(), event.getId(), event.getEventType());
        log.info("Véhicule {} repositionnement vers centroïde annulé)", vehicle.getId());
        repositioningVehicles.remove(vehicle.getId());
        
        vehicleStates.put(vehicle.getId(), VehicleState.MOVING);
        assignedEvents.add(event.getId());
        vehicleMovementThread.moveVehicleToEvent(
                vehicle, event, teamUuid, // pareil, le teamUuid ne sert à rien ici on peut l'enlever, et de moveVehicleToEvent aussi et ... ???
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