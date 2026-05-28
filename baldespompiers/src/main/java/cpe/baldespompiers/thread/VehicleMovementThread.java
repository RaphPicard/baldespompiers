package cpe.baldespompiers.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cpe.baldespompiers.client.RpEventClient;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.client.FacilityClient;
import cpe.baldespompiers.client.FireClient;
import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.EmergencyEventDto;
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.type.VehicleType;
import cpe.baldespompiers.service.EmergencyManagerService;
import cpe.baldespompiers.service.FireService;
import cpe.baldespompiers.service.RPEventService;
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
import java.util.Optional;

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
    private final RpEventClient rpEventClient;
    private final EmergencyManagerService emergencyManagerService;
    private final FireService fireService;
    private final RPEventService rpEventService;

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

    @Value("${dispatch.abandon.intensity:4}")
    private int abandonIntensity;

    // Seuils d'abandon de mission (mêmes valeurs que dans EmergencyManagerService)
    @Value("${dispatch.give_up.fuel:10.0}")
    private float giveUpFuel;
    @Value("${dispatch.give_up.liquid:0.0}")
    private float giveUpLiquid;

    @Value("${dispatch.min.fuelForNewMission:15.0}")
    private float minFuelForNewMission;

    @Value("${dispatch.min.liquidForNewMission:15.0}")
    private float minLiquidForNewMission;

    // Seuils "prêt" : atteints à la caserne avant de libérer le véhicule pour un nouveau dispatch
    @Value("${dispatch.ready.fuel:40.0}")
    private float readyFuel;

    @Value("${dispatch.ready.liquid:40.0}")
    private float readyLiquid;

    @Autowired
    public VehicleMovementThread(VehicleClient vehicleClient,
                                 FireClient fireClient,
                                 FacilityClient facilityClient,
                                 RpEventClient rpEventClient,
                                 @Lazy EmergencyManagerService emergencyManagerService, // Lazy pour éviter la dépendance circulaire (EmergencyManagerService dépend de VehicleMovementThread)
                                 @Lazy FireService fireService,
                                 @Lazy RPEventService rpEventService) {
        this.vehicleClient = vehicleClient;
        this.fireClient = fireClient;
        this.facilityClient = facilityClient;
        this.rpEventClient           = rpEventClient;
        this.emergencyManagerService = emergencyManagerService;
        this.fireService = fireService;
        this.rpEventService = rpEventService;
    }

    // ── Signal d'abandon de mission ───────────────────────────────────────────
    // RuntimeException sans stack trace pour ne pas polluer les logs.
    private static final class InsufficientResourcesException extends RuntimeException {
        InsufficientResourcesException(String msg) { super(msg, null, true, false); }
    }
    // Signal de reprise de mission : levée pendant un retour caserne si recallMode passe à OFF
    private static final class ResumeMissionException extends RuntimeException {
        ResumeMissionException(String msg) { super(msg, null, true, false); }
    }
    // Signal : repositionnement annulé car un dispatch est arrivé
    private static final class RepositioningCancelledException extends RuntimeException {
        RepositioningCancelledException() { super(null, null, true, false); }
    }
    // Signal : feu éteint par une autre équipe pendant le trajet vers lui
    private static final class FireGoneException extends RuntimeException {
        FireGoneException(String msg) { super(msg, null, true, false); }
    }

    /** Contexte d'un déplacement : utilisé pour interpréter l'état recallMode. */
    public enum MovePhase {
        TO_FIRE,            // recall actif → abandonne, retourne caserne
        TO_EVENT,           // idem TO_FIRE mais cible un event (blessé/accident)
        TO_FACILITY,        // ignore recallMode (déplacement libre)
        MANUAL,             // retour recall : interruptible si recall désactivé en cours de route
        TO_REPOSITION       // repositionnement vers centroïde : annulable si dispatch reçu
    }

    // ── Après exctinction d'un feu ? ───────────────────────────────────────────
    private boolean vehicleNeedsRecharge(VehicleDto v) {
        if (v.getFuelQuantity() < minFuelForNewMission) return true; // carburant trop bas pour une nouvelle mission
        // Pour les véhicules avec réservoir (camions, pas ambulances) : vérifie aussi le liquide extincteur
        return v.getType() != null && v.getType().getLiquidCapacity() > 0 && v.getLiquidQuantity() < minLiquidForNewMission;
    }

    // ── Point d'entrée principal ───────────────────────────────────────────────
    // le @Async va permettre de lancer ce processus de déplacement dans un thread séparé, sans bloquer le thread principal du simulateur.
    @Async("vehicleMovementExecutor")
    public void moveVehicle(VehicleDto vehicle, FireDto initialFire, String teamUuid, Runnable onDone) { // appelé dans le dispatch de emergencyManagerService
        FireDto currentFire = initialFire;
        boolean needsRecharge  = false;
        boolean willReposition = false; // true uniquement si next.isEmpty() avec des feux actifs → charge à 100%
        // Refetch position réelle : le VehicleDto du poller peut être biaisé si le véhicule vient de rentrer à la caserne
        VehicleDto fresh = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
        if (fresh != null) { vehicle.setLon(fresh.getLon()); vehicle.setLat(fresh.getLat()); }
        try {
            while (true) {
                // Phase 1 : déplacer le véhicule vers le feu (mode téléport, ligne droite ou route selon config)
                try {
                    movement_type(vehicle, teamUuid, currentFire.getLon(), currentFire.getLat(), MovePhase.TO_FIRE, currentFire.getId());
                } catch (FireGoneException e) {
                    log.info("Véhicule {} : feu #{} éteint en route — recherche d'un autre feu", vehicle.getId(), currentFire.getId());
                    FireDto redirect = redirectAfterFireGone(vehicle, currentFire); // recherche autre feu si le feu a été eteint en route
                    if (redirect == null) { needsRecharge = true; break; } // aucun feu disponible → retour caserne avant libération
                    currentFire = redirect;
                    continue;
                }
                // Met à jour la position locale pour que les prochains calculs de distance partent du bon endroit
                vehicle.setLon(currentFire.getLon());   // fix d'un bug... (à enlever ???)
                vehicle.setLat(currentFire.getLat());

                // Phase 2 : marquer le véhicule comme "sur le feu" puis attendre l'extinction
                // (le simulateur réduit l'intensité du feu automatiquement quand un véhicule est à sa position)
                emergencyManagerService.getVehicleStates()
                        .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);
                log.info("Véhicule {} arrivé sur feu #{} — attente extinction", vehicle.getId(), currentFire.getId());
                waitForFireOut(currentFire.getId(), vehicle.getId()); // bloque ici jusqu'à extinction ou ressources épuisées

                // Relit les vraies ressources depuis le simulateur (carburant et liquide ont diminué pendant la mission)
                VehicleDto refreshed = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
                if (refreshed == null) { needsRecharge = true; break; } // si le véhicule a disparu du simulateur (erreur, suppression…) → on considère qu'il doit rentrer à la caserne pour se "recharger" (reset)
                // Met à jour la position locale pour que les prochains calculs de distance partent du bon endroit
                vehicle.setLon(refreshed.getLon());
                vehicle.setLat(refreshed.getLat());

                // Si les ressources sont trop basses, le véhicule doit rentrer à la caserne (break --> finally) se recharger
                if (vehicleNeedsRecharge(refreshed)) {
                    needsRecharge = true;
                    log.warn("[Mission] Véhicule {} : ressources insuffisantes après feu #{} (fuel={} liquid={}) --> Retour CASERNE",
                            vehicle.getId(), currentFire.getId(),
                            refreshed.getFuelQuantity(), refreshed.getLiquidQuantity());
                    break;
                } //break sort de la boucle While(true) et va au error/exeption et finally !!!

                // Mode rappel (global ou individuel) : retour immédiat à la caserne, même si ressources OK
                if (emergencyManagerService.isRecallRequested(vehicle.getId())) { needsRecharge = true; break; } //isRecallrequested renvoie le bouléen de recall mis à true par l'appui du boutton "Rappeler" en html OU si le véhicule est rappelé individuellement

                // Ressources suffisantes : cherche un autre feu à traiter directement, sans passer par la caserne
                List<FireDto> activeFires = fireClient.getAllFires();
                Optional<FireDto> next = fireService.findNextFireForVehicle(
                        refreshed, activeFires != null ? activeFires : List.of());

                if (next.isEmpty()) {
                    // Aucun feu disponible / compatible : si des feux actifs existent → charge à 100% puis reposition, sinon retour caserne normal
                    List<FireDto> activeForReposition = (activeFires != null ? activeFires : List.<FireDto>of()).stream() // <~> ca veut dire que si activeFires est null, on utilise une liste vide pour éviter les NullPointerException
                            .filter(f -> f.getIntensity() > abandonIntensity).toList();
                    willReposition = !activeForReposition.isEmpty(); // on repositionne si on peut calculer les centroïdes ==> si ya des feux actifs
                    needsRecharge  = true;
                    break;
                }

                FireDto nextFire = next.get();
                log.info("Véhicule {} : ressources suffisantes (liquid = {}, fuel = {}), direct sur feu #{} (sans caserne)",
                        vehicle.getId(),vehicle.getLiquidQuantity(), vehicle.getFuelQuantity(), nextFire.getId());
                // Réserve le nouveau feu atomiquement et libère l'ancien pour les autres véhicules
                fireService.claimFire(vehicle.getId(), currentFire.getId(), nextFire.getId());
                currentFire = nextFire; // reboucle sur la phase 1 avec le nouveau feu
            }

        } catch (InsufficientResourcesException e) { // si jamais plus d'essence/liquide OU RAPPEL DES/DU véhicule demandé en cours de mission → on considère que le véhicule doit rentrer à la caserne pour se "recharger" (reset)
            needsRecharge = true;
            log.warn("[Mission] Véhicule {} abandonne la mission (feu #{}) car — {}", vehicle.getId(), currentFire.getId(), e.getMessage());

        } catch (InterruptedException | IOException e) { // interruption du thread (rappel global désactivé en cours de route) OU erreur de communication avec le simulateur → on considère que le véhicule doit rentrer à la caserne pour se "recharger" (reset)
            needsRecharge = true;
            Thread.currentThread().interrupt();
            log.error("[Move] Interruption/IO véhicule {} : {}", vehicle.getId(), e.getMessage());
        } catch (Exception e) {
            needsRecharge = true;
            log.error("[Move] Erreur inattendue véhicule {} ({}) : {}", vehicle.getId(), e.getClass().getSimpleName(), e.getMessage());

        } finally {
            try {
                fireService.releaseFire(currentFire.getId());
                if (needsRecharge) {
                    try {
                        // Rappel forcé → caserne d'origine ; recharge normale → caserne la plus proche
                        boolean isRecall = emergencyManagerService.isRecallMode()
                                       || emergencyManagerService.isRecallRequested(vehicle.getId());
                        FacilityDto target = isRecall ? null : nearestFacility(vehicle);
                        returnToFacility(vehicle, target);
                        waitForRecharge(vehicle.getId(), willReposition, target); // true → charge à 100% (avant reposition), false → seuils ready (dispatch rapide)
                        if (willReposition) repositionToCentroid(vehicle, null);
                    } catch (ResumeMissionException e) {
                        // recallMode désactivé en cours de retour → on libère le véhicule
                        // (le prochain dispatch le récupèrera, qu'il soit ou non à la caserne)
                        log.info("Véhicule {} : retour interrompu pour reprise mission", vehicle.getId());
                    }
                }
            } catch (Exception e) {
                log.error("[Move] Erreur retour/recharge véhicule {} : {}", vehicle.getId(), e.getMessage());
            }
            if (onDone != null) onDone.run(); // signale que le processus de déplacement est terminé, pour que le véhicule puisse être redispatché si besoin (dans le cas où il n'est pas à la caserne, il sera redispatché directement sans attendre la recharge complète)
        }
    }

    private boolean repositionToCentroid(VehicleDto vehicle, List<FireDto> knownFires) {
        List<FireDto> all = (knownFires != null) ? knownFires : fireClient.getAllFires();
        if (all == null) return false;
        List<FireDto> active = all.stream().filter(f -> f.getIntensity() > 0).toList(); // on garde les feux pas eteints (guard)
        if (active.isEmpty()) return false;
        double cLon = active.stream().mapToDouble(FireDto::getLon).average().orElse(0); // on calcule les centroïdes
        double cLat = active.stream().mapToDouble(FireDto::getLat).average().orElse(0);
        emergencyManagerService.repositionVehicle(vehicle, cLon, cLat);
        return true;
    }
    // |
    // |
    // v

    /**
     * Déplace un véhicule idle vers le centroïde des feux actifs.
     * Interruptible à chaque pas si un dispatch arrive (isRepositioning() revient false).
     * Wrapper public @Async autour de movement_type, utilisé par /api/vehicles/{id}/move
     * pour respecter la vitesse max du simulateur.
     */
    @Async("vehicleMovementExecutor")
    public void repositionVehicle(VehicleDto vehicle, double clon, double clat) {
        boolean arrived = false;
        try {
            movement_type(vehicle, teamUuid, clon, clat, MovePhase.TO_REPOSITION, null);
            arrived = true; // reste dans repositioningVehicles → le prochain tick ne relance pas recallIdleVehicle
            log.debug("Véhicule {} arrivé au centroïde ({}, {})", vehicle.getId(), clon, clat);
        } catch (RepositioningCancelledException e) {
            log.debug("Véhicule {} : repositionnement annulé (dispatch reçu)", vehicle.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("Repositionnement véhicule {} : {}", vehicle.getId(), e.getMessage());
        } finally {
            if (!arrived) emergencyManagerService.stopRepositioning(vehicle.getId()); // erreur ou annulation → libère le marqueur
            // si arrived=true : on reste dans repositioningVehicles jusqu'au prochain dispatch() qui se chargera de lever l'exeption RepositioningCancelledException
        }
    }

    // ── moveVehicleToEvent (accidents/blessés) ────────────────────────────────
    @Async("vehicleMovementExecutor")
    public void moveVehicleToEvent(VehicleDto vehicle, EmergencyEventDto initialEvent,
                                   String teamUuid, Runnable onDone) {
        EmergencyEventDto currentEvent = initialEvent;
        boolean needsRecharge  = false;
        boolean willReposition = false;
        // Refetch position réelle : le VehicleDto du poller peut être stale si le véhicule vient de rentrer à la caserne
        VehicleDto fresh = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
        if (fresh != null) { vehicle.setLon(fresh.getLon()); vehicle.setLat(fresh.getLat()); }
        try {
            while (true) {
                // Phase 1 : aller sur l'event, en détectant si l'event est résolu en route
                try {
                    movement_type(vehicle, teamUuid,
                            currentEvent.getLon(), currentEvent.getLat(), MovePhase.TO_EVENT, currentEvent.getId());
                } catch (FireGoneException e) {
                    log.info("Véhicule {} : event #{} résolu en route — recherche d'un autre event",
                            vehicle.getId(), currentEvent.getId());
                    EmergencyEventDto redirect = redirectAfterEventGone(vehicle, currentEvent);
                    if (redirect == null) { needsRecharge = true; break; } // aucun event disponible → retour caserne avant replacement centroïdes
                    currentEvent = redirect;
                    continue;
                }
                vehicle.setLon(currentEvent.getLon());
                vehicle.setLat(currentEvent.getLat());

                // Phase 2 : intervention
                emergencyManagerService.getVehicleStates()
                        .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);
                log.info("Véhicule {} arrivé sur event #{} — attente résolution",
                        vehicle.getId(), currentEvent.getId());
                waitForEventOut(currentEvent.getId(), vehicle.getId());

                // Relit les vraies ressources depuis le simulateur
                VehicleDto refreshed = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
                if (refreshed == null || vehicleNeedsRecharge(refreshed)) {
                    needsRecharge = true;
                    break; // on envoie à la recharge avec willReposition = False pour une charge rapide
                }
                vehicle.setLon(refreshed.getLon());
                vehicle.setLat(refreshed.getLat());

                // Mode rappel : retour caserne immédiat
                if (emergencyManagerService.isRecallRequested(vehicle.getId())) { needsRecharge = true; break; }

                // Ressources suffisantes : cherche un autre event directement, sans passer par la caserne
                List<EmergencyEventDto> activeEvents = rpEventClient.getAllEvents();
                Optional<EmergencyEventDto> next = rpEventService.findNextEventForVehicle(
                        refreshed, activeEvents != null ? activeEvents : List.of());

                if (next.isEmpty()) {
                    List<FireDto> firesForReposition = fireClient.getAllFires();
                    willReposition = firesForReposition != null &&
                            firesForReposition.stream().anyMatch(f -> f.getIntensity() > abandonIntensity);
                    needsRecharge = true; // on renvoie à la recharge avec willReposition = true Pour une charge à 100% avant redirection centroïdes
                    break;
                }

                EmergencyEventDto nextEvent = next.get();
                log.info("Véhicule {} : ressources suffisantes (fuel={} liquid={}), direct sur event #{} (sans caserne)",
                        vehicle.getId(), refreshed.getFuelQuantity(), refreshed.getLiquidQuantity(), nextEvent.getId());
                rpEventService.claimEvent(vehicle.getId(), currentEvent.getId(), nextEvent.getId());
                currentEvent = nextEvent;
            }

        } catch (InsufficientResourcesException e) {
            needsRecharge = true;
            log.warn("[Event] Véhicule {} abandonne event #{} — {}",
                    vehicle.getId(), currentEvent.getId(), e.getMessage());
        } catch (InterruptedException | IOException e) {
            needsRecharge = true;
            Thread.currentThread().interrupt();
            log.error("[Event] Véhicule {} : {}", vehicle.getId(), e.getMessage());
        } catch (Exception e) {
            needsRecharge = true;
            log.error("[Event] Erreur véhicule {} : {}", vehicle.getId(), e.getMessage());
        } finally {
            try {
                rpEventService.releaseEvent(currentEvent.getId());
                if (needsRecharge) {
                    try {
                        boolean isRecall = emergencyManagerService.isRecallMode()
                                       || emergencyManagerService.isRecallRequested(vehicle.getId());
                        FacilityDto target = isRecall ? null : nearestFacility(vehicle); //retour à la caserne la plus proche sauf si rappel forcé (caserne d'origine)
                        returnToFacility(vehicle, target);
                        waitForRecharge(vehicle.getId(), willReposition, target);
                        if (willReposition) repositionToCentroid(vehicle, null); // on ne repositionne aux centroïdes que si il n'a rien à faire
                    } catch (ResumeMissionException e) {
                        log.info("Véhicule {} : retour event interrompu", vehicle.getId());
                    }
                }
            } catch (Exception e) {
                log.error("[Event] Erreur retour véhicule {} : {}", vehicle.getId(), e.getMessage());
            }
            if (onDone != null) onDone.run();
        }
    }

    // ── Redirection après event résolu en route ────────────────────────────────

    /**
     * Miroir de redirectAfterFireGone pour les events (blessés/accidents).
     * Libère l'event résolu, cherche un autre via findNextEventForVehicle et claimEvent.
     */
    private EmergencyEventDto redirectAfterEventGone(VehicleDto vehicle, EmergencyEventDto resolvedEvent) {
        rpEventService.releaseEvent(resolvedEvent.getId());

        VehicleDto refreshed = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
        if (refreshed != null) {
            vehicle.setLon(refreshed.getLon());
            vehicle.setLat(refreshed.getLat());
        }

        List<EmergencyEventDto> activeEvents = rpEventClient.getAllEvents();
        Optional<EmergencyEventDto> next = rpEventService.findNextEventForVehicle(
                refreshed != null ? refreshed : vehicle,
                activeEvents != null ? activeEvents : List.of());

        if (next.isEmpty()) {
            log.info("Véhicule {} : event #{} résolu en route, aucun autre disponible — libération",
                    vehicle.getId(), resolvedEvent.getId());
            return null;
        }

        EmergencyEventDto nextEvent = next.get();
        rpEventService.claimEvent(vehicle.getId(), resolvedEvent.getId(), nextEvent.getId()); // claimEvent loggue déjà la transition
        return nextEvent;
    }

    /**
     * Rappel d'un véhicule inactif (pas en mission) vers sa caserne. Pour ensuite le positionner au centroïde.
     * Utilisé par le recall-all pour rapatrier les véhicules qui n'ont plus de thread actif. Et aussi pour les véhicules qui sont dispatchés alors qu'ils étaient en repositionnement (dispatch qui arrive pendant le repositionnement → annule le repositionnement et le véhicule peut se faire dispatcher)
     * Wrapper public @Async autour de movement_type, utilisé par /api/vehicles/{id}/move
     * pour respecter la vitesse max du simulateur.
     */
    @Async("vehicleMovementExecutor")
    public void recallIdleVehicle(VehicleDto vehicle, Runnable onDone) {
        boolean repositioningStarted = false;
        try {
            returnToFacility(vehicle, null); // rappel idle → toujours caserne d'origine
            if (!emergencyManagerService.isRepositioning(vehicle.getId())) throw new RepositioningCancelledException();
            waitForRecharge(vehicle.getId(), true, null);
            if (!emergencyManagerService.isRepositioning(vehicle.getId())) throw new RepositioningCancelledException();

            VehicleDto atFacility = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
            if (atFacility != null) { vehicle.setLon(atFacility.getLon()); vehicle.setLat(atFacility.getLat()); }
            repositioningStarted = repositionToCentroid(vehicle, null);

        } catch (RepositioningCancelledException e) {
            log.debug("Véhicule inactif {} : repositionnement annulé (dispatché en cours de route)", vehicle.getId());
        } catch (ResumeMissionException e) {
            log.info("Véhicule inactif {} : retour annulé (rappel désactivé en cours de route)", vehicle.getId());
        } catch (Exception e) {
            log.warn("Rappel véhicule inactif {} : {}", vehicle.getId(), e.getMessage());
        } finally {
            if (!repositioningStarted) emergencyManagerService.stopRepositioning(vehicle.getId()); // si repositionToCentroid n'a pas démarré, on nettoie nous-mêmes (si pas de feux actif). Sinon c'est le dispatch qui lèvera l'exeption
            if (onDone != null) onDone.run();
        }
    }


    /**
     * Déplacement progressif vers une coordonnée arbitraire (sans logique feu/caserne).
     * Wrapper public @Async autour de movement_type, utilisé par /api/vehicles/{id}/move
     * pour respecter la vitesse max du simulateur.
     */
    @Async("vehicleMovementExecutor")
    public void moveTo(VehicleDto vehicle, double lon, double lat) {
        try {
            movement_type(vehicle, teamUuid, lon, lat, MovePhase.TO_FACILITY, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Déplacement interrompu pour véhicule {}", vehicle.getId());
        } catch (IOException e) {
            log.error("Erreur déplacement véhicule {} : {}", vehicle.getId(), e.getMessage());
        }
    }

    // ── Sélection du mode de déplacement ──────────────────────────────────────

    private void movement_type(VehicleDto vehicle, String teamUuid, double lon, double lat, MovePhase phase, Integer targetId)
            throws InterruptedException, IOException {
        long vehicleDelay = computeStepDelay(vehicle.getType());

        if ("straight".equals(movementMode)) {
            moveToPoint(vehicle.getLon(), vehicle.getLat(), lon, lat, vehicle.getId(), vehicleDelay, phase, targetId);

        } else if ("road".equals(movementMode)) {
            moveFollower(vehicle, lon, lat, teamUuid, vehicleDelay, phase, targetId);

        } else {
            teleport(vehicle.getId(), lon, lat);
        }
    }

    /**
     * Calcule le délai (ms) entre deux pas selon la vitesse max du type de véhicule.
     * Référence : 110 km/h → stepDelayMs.
     *
     *   CAR              (150 km/h) → stepDelayMs × 110/150 ≈ ×0.73  (plus rapide)
     *   FIRE_ENGINE      (110 km/h) → stepDelayMs × 1.00              (référence)
     *   PUMPER_TRUCK     ( 70 km/h) → stepDelayMs × 110/70  ≈ ×1.57  (plus lent)
     */
    private long computeStepDelay(VehicleType type) {
        if (type == null) return stepDelayMs; // type inconnu → délai par défaut
        float speed = type.getMaxSpeed();
        if (speed <= 0) return stepDelayMs; // vitesse invalide → délai par défaut
        // Plus le véhicule est rapide, plus le délai entre deux pas est court (rapport inversement proportionnel)
        return (long) (stepDelayMs * 80.0 / speed);
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
     */
    private void moveFollower(VehicleDto vehicle,
                              double targetLon, double targetLat,
                              String teamUuid, long vehicleDelay, MovePhase phase, Integer targetId)
            throws IOException, InterruptedException {

        // Construit l'URL OSRM : "lon_départ,lat_départ;lon_cible,lat_cible" avec géométrie complète
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
            // OSRM injoignable ou surchargé → on déplace quand même le véhicule en ligne droite
            log.warn("[OSRM] HTTP {} — fallback ligne droite", response.statusCode());
            moveToPoint(vehicle.getLon(), vehicle.getLat(), targetLon, targetLat, vehicle.getId(), vehicleDelay, phase, targetId);
            return;
        }

        JsonNode root = objectMapper.readTree(response.body());

        if (!"Ok".equals(root.path("code").asText())) {
            // OSRM n'a pas trouvé de route (feu hors réseau routier, zone isolée…) → fallback ligne droite
            log.warn("[OSRM] {} — fallback ligne droite", root.path("code").asText());
            moveToPoint(vehicle.getLon(), vehicle.getLat(), targetLon, targetLat, vehicle.getId(), vehicleDelay, phase, targetId);
            return;
        }

        // Navigue dans le JSON OSRM pour extraire les coordonnées de l'itinéraire
        JsonNode coordinates = root
                .path("routes").get(0)
                .path("geometry")
                .path("coordinates");

        if (coordinates == null || !coordinates.isArray()) {
            // Réponse malformée → fallback ligne droite pour ne pas bloquer le véhicule
            log.warn("[OSRM] Pas de coordonnées — fallback ligne droite");
            moveToPoint(vehicle.getLon(), vehicle.getLat(), targetLon, targetLat, vehicle.getId(), vehicleDelay, phase, targetId);
            return;
        }

        // Convertit chaque nœud JSON [lon, lat] en tableau de doubles exploitable
        List<double[]> waypoints = new ArrayList<>();
        for (JsonNode coord : coordinates) {
            waypoints.add(new double[]{ coord.get(0).asDouble(), coord.get(1).asDouble() });
        }

        log.info("[OSRM] {} waypoints pour véhicule {}, phase={}", waypoints.size(), vehicle.getId(), phase);

        // Parcourt chaque segment de route un par un (de waypoint[i-1] à waypoint[i])
        for (int i = 1; i < waypoints.size(); i++) {
            double[] from = waypoints.get(i - 1);
            double[] to   = waypoints.get(i);
            // pas besoin de reverifier les exeptions ... car déjà verif dans moveToPoint
            moveToPoint(from[0], from[1], to[0], to[1], vehicle.getId(), vehicleDelay, phase, targetId);
        }

        // OSRM s'arrête à la route la plus proche. Si le feu est en dehors du réseau (forêt, champ…),
        // on parcourt le tronçon restant en ligne droite pour atteindre la position exacte du feu.
        if (!waypoints.isEmpty()) {
            double[] last = waypoints.get(waypoints.size() - 1);
            double dLon = targetLon - last[0];
            double dLat = targetLat - last[1];
            // peut etre : enlever le if et mettre dans tous les cas moveToPoint
            if (Math.sqrt(dLon * dLon + dLat * dLat) > stepSize) {
                moveToPoint(last[0], last[1], targetLon, targetLat, vehicle.getId(), vehicleDelay, phase, targetId);
            }
        }

        log.info("[OSRM] Véhicule {} arrivé à destination", vehicle.getId());
    }





    // ── Téléportation ─────────────────────────────────────────────────────────

    private void teleport(Integer vehicleId, double lon, double lat) {
        vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(lon, lat));
    }






    // ── Interpolation pas à pas (partagée par straight et road) ───────────────

    /**
     * Déplace le véhicule pas à pas de (fromLon, fromLat) vers (toLon, toLat). --> Move STRAIGHT (tout droit)
     * Lance InsufficientResourcesException si le carburant passe sous minFuel en cours de route.
     */
    private void moveToPoint(double fromLon, double fromLat,
                             double toLon, double toLat,
                             Integer vehicleId, long vehicleDelay, MovePhase phase, Integer targetId) throws InterruptedException {
        double currentLon = fromLon;
        double currentLat = fromLat;
        long lastCheck = System.currentTimeMillis(); // pour vérifier périodiquement si le feu/event a été résolu en route, sans faire de check à chaque pas (pour ne pas surcharger le simulateur et les logs) --> on check toutes les fireCheckDelayMs ms

        while (true) {

            // Check du mode rappel (global ou individuel) à chaque pas → abandonne immédiatement
            if ((phase == MovePhase.TO_FIRE || phase == MovePhase.TO_EVENT)
                    && emergencyManagerService.isRecallRequested(vehicleId))
                throw new InsufficientResourcesException("rappel actif — abandon trajet");
            // Retour caserne : interrompu UNIQUEMENT si recallMode global devient OFF (les rappels individuels doivent finir leur trajet retour)
            if (phase == MovePhase.MANUAL && !emergencyManagerService.isRecallMode()
                    && !emergencyManagerService.isRecallRequested(vehicleId))
                throw new ResumeMissionException("rappel terminé — abandon retour caserne");
            // Repositionnement : annulé si dispatch reçu (le remove de repositioningVehicles est fait dans dispatch())
            if (phase == MovePhase.TO_REPOSITION && !emergencyManagerService.isRepositioning(vehicleId))
                throw new RepositioningCancelledException();





            // Vérifie périodiquement si la cible (feu ou event) a été résolue en cours de route
            if (targetId != null) {
                long now = System.currentTimeMillis();
                if (now - lastCheck >= fireCheckDelayMs) { // si le délai depuis le dernier check dépasse fireCheckDelayMs → on vérifie l'état du feu/event ciblé
                    lastCheck = now;
                    if (phase == MovePhase.TO_FIRE) {
                        FireDto fire = fireClient.getFireById(targetId);
                        int fireThreshold = (fire != null && fireService.isCaserneFire(fire)) ? 0 : abandonIntensity; // seuil d'abandon à 0 si le feu est sur notre caserne
                        if (fire == null || fire.getIntensity() <= fireThreshold) // si le feu a disparu ou est quasi-éteint (intensité ≤ seuil d'abandon) → abandonne la mission et cherche un autre feu (redirection dans moveVehicle)
                            throw new FireGoneException("feu #" + targetId + " quasi-éteint (≤" + fireThreshold + ") — laissé aux autres équipes");
                    } else if (phase == MovePhase.TO_EVENT) {
                        EmergencyEventDto ev = targetId < 0 // les events "fake" construits à partir de feux ont des IDs négatifs pour les différencier des events réels (accidents/blessés) qui ont des IDs positifs
                                ? buildFakeEventFromFire(-targetId) // si ID négatif → construit un event factice à partir du feu (pour les feux qui ne sont pas déjà des events, ex : feux de forêt)
                                : rpEventClient.getEventById(targetId);
                        if (ev == null) {
                            throw new FireGoneException("event #" + targetId + " disparu ou résolu");
                        }
                        if (ev.getIntensity() <= 0) {
                            boolean allTreated = ev.getInjuredPeopleDtoList() == null
                                    || ev.getInjuredPeopleDtoList().isEmpty()
                                    || ev.getInjuredPeopleDtoList().stream()
                                    .allMatch(p -> p.getInjuryDto() == null
                                            || p.getInjuryDto().getIntensity() <= 0);
                            if (allTreated) throw new FireGoneException("event #" + targetId + " résolu en route");
                        }
                    }
                }
            }


            // Calcule le vecteur restant à parcourir et sa longueur (en degrés)
            double dLon = toLon - currentLon;
            double dLat = toLat - currentLat;
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);
            if (dist <= stepSize) {
                // Distance restante inférieure à un pas : on saute directement sur la destination exacte
                vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), // teleportation final pour éviter les erreurs d'arrondi qui empêcheraient d'atteindre exactement la position du feu/event
                        new Coord(toLon, toLat));
                Thread.sleep(vehicleDelay);
                break;
            }

            // Avance d'exactement stepSize dans la direction de la destination (normalisation du vecteur)
            double ratio = stepSize / dist; // ratio c'est à dire la proportion du vecteur total à parcourir pour faire un pas de taille stepSize
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            // Envoie la nouvelle position au simulateur et récupère l'état mis à jour du véhicule
            VehicleDto updated = vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                    new Coord(currentLon, currentLat));
            Thread.sleep(vehicleDelay); // attend avant le prochain pas (simule la vitesse du véhicule)

            // Coupe la mission si le carburant est trop bas — uniquement en route vers un feu/event, jamais en retour caserne

            if ((phase == MovePhase.TO_FIRE || phase == MovePhase.TO_EVENT)
                    && updated != null && updated.getFuelQuantity() < giveUpFuel)
                throw new InsufficientResourcesException("carburant insuffisant (fuel=" + updated.getFuelQuantity() + ")");
        }
    }




    // ── Redirection après feu éteint en route ─────────────────────────────────

    /**
     * Appelée quand le feu cible est éteint avant que le véhicule n'y arrive.
     * Libère le feu éteint, cherche un autre feu via findNextFireForVehicle et claimFire.
     * Retourne le nouveau feu, ou null si aucun feu n'est disponible (le véhicule sera libéré).
     */
    private FireDto redirectAfterFireGone(VehicleDto vehicle, FireDto deadFire) {
        fireService.releaseFire(deadFire.getId());

        // Relit la position réelle du véhicule (il s'est arrêté en chemin)
        VehicleDto refreshed = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
        if (refreshed != null) {
            vehicle.setLon(refreshed.getLon());
            vehicle.setLat(refreshed.getLat());
        }

        List<FireDto> activeFires = fireClient.getAllFires();
        Optional<FireDto> next = fireService.findNextFireForVehicle(
                refreshed != null ? refreshed : vehicle,
                activeFires != null ? activeFires : List.of());

        if (next.isEmpty()) {
            log.info("Véhicule {} : feu #{} éteint en route, aucun autre feu disponible — libération",
                    vehicle.getId(), deadFire.getId());
            return null;
        }

        FireDto nextFire = next.get();
        fireService.claimFire(vehicle.getId(), deadFire.getId(), nextFire.getId()); // claimFire loggue déjà la transition
        log.info("Véhicule {} : redirigé vers feu #{} (car feu #{} éteint en route par une autre équipe)", vehicle.getId(), nextFire.getId(), deadFire.getId());
        return nextFire;
    }



    // ── Attente extinction du feu ─────────────────────────────────────────────

    /**
     * Attend que le feu soit éteint. Lance InsufficientResourcesException si le
     * véhicule manque de liquide avant l'extinction (uniquement pour les véhicules
     * dont le type a une capacité liquide > 0 ==> pour que ca traite pas les ambulances).
     */
    private void waitForFireOut(Integer fireId, Integer vehicleId) throws InterruptedException {
        FireDto initial = fireClient.getFireById(fireId);
        int threshold = (initial != null && fireService.isCaserneFire(initial)) ? 0 : abandonIntensity; // seuil d'abandon à 0 si le feu est sur notre caserne !!!
        float lastIntensity = -1;

        while (true) {

            // Mode rappel (global ou individuel) : abandonne immédiatement → catch → retour caserne
            if (emergencyManagerService.isRecallRequested(vehicleId)) {  // un véhicule est rajouté à une liste de véhicule à rappeler. Par ex dans : handleCasernefire
                throw new InsufficientResourcesException("rappel actif");
            }

            // STRATEGIE de laisser à une certaine intensité pour berner les autres equipes
            FireDto current = fireClient.getFireById(fireId);
            if (current == null || current.getIntensity() <= threshold) {
                log.info("Feu #{} éteint ou quasi-éteint (intensité={}), laissé pour les autres équipes : abandonné par véhicule {}",
                        fireId, current != null ? current.getIntensity() : "N/A", vehicleId);
                break;
            }

            // Vérifie le niveau de liquide uniquement pour les véhicules avec réservoir (pas les ambulances)
            VehicleDto vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
            if (vehicle != null && vehicle.getType() != null
                    && vehicle.getType().getLiquidCapacity() > 0
                    && vehicle.getLiquidQuantity() < giveUpLiquid)
                // Plus assez de liquide pour continuer → abandonne la mission et rentre à la caserne
                throw new InsufficientResourcesException("liquide insuffisant (liquid=" + vehicle.getLiquidQuantity() + ")");

            float intensity = current.getIntensity();
            if (intensity != lastIntensity) { // pour éviter de logguer à chaque pas quand l'intensité ne change pas
                log.info("[Feu #{}] intensité = {}", fireId, intensity);
                lastIntensity = intensity;
            }
            Thread.sleep(fireCheckDelayMs); // toutes les 3s, vérifie à nouveau l'état du feu et les ressources du véhicule
        }
    }

    // il n' y a pas deja une methode qui fait ca ???
    private EmergencyEventDto buildFakeEventFromFire(Integer fireId) {
        FireDto fire = fireClient.getFireById(fireId);
        if (fire == null || fire.getInjuredPeopleDtoList() == null
                || fire.getInjuredPeopleDtoList().isEmpty()) return null;
        EmergencyEventDto e = new EmergencyEventDto();
        e.setId(-fireId);
        e.setEventType(cpe.baldespompiers.model.type.EmergencyType.PERSONAL_INJURY);
        e.setIntensity(0f);
        e.setLon(fire.getLon());
        e.setLat(fire.getLat());
        e.setInjuredPeopleDtoList(fire.getInjuredPeopleDtoList());
        return e;
    }


    // ── Attente résolution event ──────────────────────────────────────────────
    private void waitForEventOut(Integer eventId, Integer vehicleId) throws InterruptedException {
        float lastIntensity = -1.0f;
        while (true) {
            if (emergencyManagerService.isRecallRequested(vehicleId))
                throw new InsufficientResourcesException("rappel actif");

            EmergencyEventDto current = eventId < 0
                    ? buildFakeEventFromFire(-eventId)
                    : rpEventClient.getEventById(eventId);
            if (current == null) break;

            // Cas 1 : event avec intensité → attendre qu'elle descende à 0
            if (current.getIntensity() > 0) {
                float intensity = current.getIntensity();
                if (intensity != lastIntensity) {
                    log.info("[Event #{}] intensité = {}", eventId, intensity);
                    lastIntensity = intensity;
                }
                Thread.sleep(fireCheckDelayMs);
                continue;
            }

            // Cas 2 : intensité = 0 → vérifier si des blessés sont encore en cours de traitement
            if (current.getInjuredPeopleDtoList() == null || current.getInjuredPeopleDtoList().isEmpty()) {
                break; // pas de blessés → terminé immédiatement
            }

            boolean allTreated = current.getInjuredPeopleDtoList().stream()
                    .allMatch(p -> p.getInjuryDto() == null
                            || p.getInjuryDto().getIntensity() <= 0);

            if (allTreated) break;

            // blessés restants : log centralisé dans EventPollerThread
            Thread.sleep(fireCheckDelayMs);
        }
    }

    // ── Retour à la caserne ───────────────────────────────────────────────────

    /**
     * Trouve la caserne de notre équipe la plus proche de la position actuelle du véhicule.
     * Retourne null si aucune caserne n'est disponible (erreur API).
     */
    private FacilityDto nearestFacility(VehicleDto vehicle) {
        List<FacilityDto> all = facilityClient.getAllFacilities(teamUuid);
        if (all == null || all.isEmpty()) return null;
        return all.stream()
                .min(Comparator.comparingDouble(f -> {
                    double dLon = f.getLon() - vehicle.getLon();
                    double dLat = f.getLat() - vehicle.getLat();
                    return dLon * dLon + dLat * dLat;
                }))
                .orElse(null);
    }

    /**
     * Retourne le véhicule à la caserne cible.
     * Si {@code targetFacility} est null, utilise la caserne d'origine ({@code vehicle.getFacilityRefID()}).
     * Rappel forcé → passe toujours null pour rester sur la caserne d'origine.
     */
    private void returnToFacility(VehicleDto vehicle, FacilityDto targetFacility) throws InterruptedException, IOException {
        FacilityDto facility;
        if (targetFacility != null) {
            facility = targetFacility;
        } else {
            if (vehicle.getFacilityRefID() == null) return; // guard inutile ?
            facility = facilityClient.getFacilityById(String.valueOf(vehicle.getFacilityRefID()));
            if (facility == null) return;
        }
        VehicleDto current = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
        if (current != null) {
            vehicle.setLon(current.getLon());
            vehicle.setLat(current.getLat());
        }
        boolean isRecall = emergencyManagerService.isRecallMode()
                        || emergencyManagerService.isRecallRequested(vehicle.getId());
        MovePhase phase;
        if (isRecall) phase = MovePhase.MANUAL;
        else if (emergencyManagerService.isRepositioning(vehicle.getId())) phase = MovePhase.TO_REPOSITION;
        else phase = MovePhase.TO_FACILITY;
        if (targetFacility != null && vehicle.getFacilityRefID() != null
                && !targetFacility.getId().equals(vehicle.getFacilityRefID()))
            log.info("Véhicule {} : retour caserne la plus proche #{} '{}' (caserne d'origine #{})",
                    vehicle.getId(), targetFacility.getId(), targetFacility.getName(), vehicle.getFacilityRefID());
        movement_type(vehicle, teamUuid, facility.getLon(), facility.getLat(), phase, null);
    }

    // ── Attente du rechargement à la caserne ──────────────────────────────────

    /**
     * Attend que le véhicule soit rechargé à la caserne.
     * waitForFull=false → seuils readyFuel/readyLiquid (prêt pour dispatch)
     * waitForFull=true  → 100% de capacité (avant repositionnement au centroïde)
     * targetFacility    → caserne cible la plus proche (null = caserne d'origine via facilityRefID -> rappel forcé)
     */
    private void waitForRecharge(Integer vehicleId, boolean waitForFull, FacilityDto targetFacility) throws InterruptedException {
        final boolean wasRepositioning = emergencyManagerService.isRepositioning(vehicleId);
        VehicleDto vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
        if (vehicle == null || vehicle.getType() == null) return;

        FacilityDto facility;
        if (targetFacility != null) {
            facility = targetFacility;
        } else {
            if (vehicle.getFacilityRefID() == null) return;
            facility = facilityClient.getFacilityById(String.valueOf(vehicle.getFacilityRefID()));
            if (facility == null) return;
        }

        // 1) Attendre l'arrivée réelle à la caserne (tolérance sur coordonnées) -> pas de == avec des arrondis
        final double arrivalEpsilon = stepSize;
        while (vehicle != null) {
            double dLon = facility.getLon() - vehicle.getLon();
            double dLat = facility.getLat() - vehicle.getLat();
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);

            if (dist <= arrivalEpsilon) break; // arrivée à la caserne, on sort du while
            Thread.sleep(fireCheckDelayMs); // chek toutes les 1s
            vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
            if (wasRepositioning && !emergencyManagerService.isRepositioning(vehicleId))
                throw new RepositioningCancelledException();
        }

        // 2) Attendre que la recharge atteigne les seuils
        float fuelCap   = vehicle.getType().getFuelCapacity();
        float liquidCap = vehicle.getType().getLiquidCapacity();
        // -1 pour waitForFull (éviter d'attendre 59.9 → 60.0 jamais atteint à cause des arrondis)
        // min() pour waitForFull=false : si la capacité max du véhicule est < seuil_ready configuré, on plafonne pour éviter une boucle infinie (ex : CAR)
        float fuelThreshold   = waitForFull ? fuelCap   - 1 : Math.min(readyFuel,   fuelCap);
        float liquidThreshold = waitForFull ? liquidCap - 1 : Math.min(readyLiquid, liquidCap);

        boolean needsFuel   = vehicle.getFuelQuantity() < fuelThreshold;
        boolean needsLiquid = liquidCap > 0 && vehicle.getLiquidQuantity() < liquidThreshold;
        if (!needsFuel && !needsLiquid) return;

        log.info("Véhicule {} en rechargement à la caserne (fuel={} liquid={})",
                vehicleId, vehicle.getFuelQuantity(), vehicle.getLiquidQuantity());

        float lastFuel   = vehicle.getFuelQuantity();
        float lastLiquid = vehicle.getLiquidQuantity();

        while (true) {
            Thread.sleep(fireCheckDelayMs);
            vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
            if (vehicle == null || vehicle.getType() == null) break;

            if (wasRepositioning && !emergencyManagerService.isRepositioning(vehicleId)) // si on était en repositionnement et que le repositionnement a été annulé pendant la recharge (dispatch reçu) → on arrête tout et on libère le véhicule
                throw new RepositioningCancelledException();

            boolean fuelOk   = vehicle.getFuelQuantity() >= fuelThreshold;
            boolean liquidOk = (liquidCap == 0) || (vehicle.getLiquidQuantity() >= liquidThreshold);
            if (fuelOk && liquidOk) break; // chargé donc on sort de la boucle

            float fuel   = vehicle.getFuelQuantity();
            float liquid = vehicle.getLiquidQuantity();
            if (fuel != lastFuel || liquid != lastLiquid) {
                log.info("[Recharge #{}] fuel={} liquid={}", vehicleId, fuel, liquid);
                lastFuel   = fuel;
                lastLiquid = liquid;
            }
        }

        log.info("Véhicule {} rechargé — prêt pour repositionnement / une nouvelle mission", vehicleId);
    }

}
