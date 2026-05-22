package cpe.baldespompiers.thread;

import cpe.baldespompiers.client.FireClient;
import cpe.baldespompiers.client.RpEventClient;
import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.EmergencyEventDto;
import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.service.EmergencyManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import cpe.baldespompiers.model.type.EmergencyType;
import java.util.ArrayList;

import java.util.List;

@Component
public class EventPollerThread {

    private static final Logger log = LoggerFactory.getLogger(EventPollerThread.class);

    private final FireClient fireClient;
    private final VehicleClient vehicleClient;
    private final RpEventClient rpEventClient;
    private final EmergencyManagerService emergencyManagerService;

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    // List.of --> Collections.emptyList()
    private volatile List<FireDto>           cachedFires    = List.of(); // pour éviter les NullPointerException dans le dispatch quand le simulateur n'est pas encore prêt ou en cas d'erreur de polling
    private volatile List<VehicleDto>        cachedVehicles = List.of();
    private volatile List<EmergencyEventDto> cachedEvents   = List.of();

    private volatile int  lastFireCount       = -1; // pour éviter de spammer les logs quand rien ne change
    private volatile int  lastEventCount      = -1;
    private volatile long lastEventRemaining  = -1;



    public EventPollerThread(FireClient fireClient,
                             VehicleClient vehicleClient,
                             RpEventClient rpEventClient,
                             EmergencyManagerService emergencyManagerService) {
        this.fireClient              = fireClient;
        this.vehicleClient           = vehicleClient;
        this.rpEventClient           = rpEventClient;
        this.emergencyManagerService = emergencyManagerService;
    }

    @Scheduled(fixedDelayString = "${poller.interval-ms:5000}")
    public void pollAndDispatch() {
        try {
            List<FireDto>           fires    = fireClient.getAllFires();
            List<EmergencyEventDto> events   = rpEventClient.getAllEvents();
            List<VehicleDto>        vehicles = vehicleClient.getVehiclesByTeam(teamUuid);

            if (fires    != null) this.cachedFires    = fires;
            if (events   != null) this.cachedEvents   = events;
            if (vehicles != null) this.cachedVehicles = vehicles;

            if (fires != null && !fires.isEmpty()) {
                int fireCount = fires.size();
                if (fireCount != lastFireCount) {
                    log.info("Feux actifs : {}", fireCount);
                    lastFireCount = fireCount;
                }
                emergencyManagerService.dispatchAllFires(fires, vehicles);
            } else {
                if (lastFireCount != 0) {
                    log.info("Aucun feu actif.");
                    lastFireCount = 0;
                }
            }

            List<EmergencyEventDto> allEvents = new ArrayList<>(events != null ? events : List.of());

            // Ajouter les blessés des feux comme faux events pour les dispatcher aussi (le simulateur ne les gère pas comme des events à part entière, mais ça nous intéresse de les traiter dans le même flux que les autres events pour la logique de dispatch et de traitement des véhicules)
            if (fires != null) {
                fires.stream()
                        .filter(f -> f.getInjuredPeopleDtoList() != null && !f.getInjuredPeopleDtoList().isEmpty())
                        .forEach(f -> { // pour chaque feu avec des personnes bléssés :
                            EmergencyEventDto e = new EmergencyEventDto(); // on lui assigne son event d'mergency
                            e.setId(-f.getId()); // on lui donne un ID négatif pour éviter les conflits avec les events réels du simulateur (qui ont tous des IDs positifs)
                            e.setEventType(EmergencyType.PERSONAL_INJURY); // on le catégorise comme un event de blessé
                            e.setIntensity(0f); // l'intensité n'a pas de sens pour un event de blessé, on la met à 0
                            e.setLon(f.getLon());
                            e.setLat(f.getLat());
                            e.setInjuredPeopleDtoList(f.getInjuredPeopleDtoList());
                            allEvents.add(e); // on l'ajoute à la liste des events à dispatcher (avec tous les autres events réels du simulateur)
                        });
            }

            if (!allEvents.isEmpty()) { // eviter logs répétitifs
                int eventCount = allEvents.size();
                if (eventCount != lastEventCount) {
                    log.info("Events actifs (dont blessés sur feux comptés comme des events en plus) : {}", eventCount);
                    lastEventCount = eventCount;
                }
                long remaining = allEvents.stream() // log pour compter le nombre de personnes bléssées
                        .filter(ev -> ev.getInjuredPeopleDtoList() != null) // ev = chaque event de la liste de tous les events
                        .flatMap(ev -> ev.getInjuredPeopleDtoList().stream()) // .flatMap() = pour chaque event, on récupère sa liste de personnes bléssées (getInjuredPeopleDtoList) et on les met à plat dans un seul stream de personnes bléssées (au lieu d'avoir un stream d'events avec chacun une liste de personnes bléssées)
                        .filter(p -> p.getInjuryDto() != null && p.getInjuryDto().getIntensity() > 0) // on ne compte que les personnes bléssées avec une intensité de blessure > 0 (intensité = 0 signifie pas de blessure, donc on les ignore)
                        .count();
                if (remaining != lastEventRemaining) {
                    if (remaining > 0) log.info("{} blessé(s) restants à traiter", remaining);
                    lastEventRemaining = remaining;
                }
                emergencyManagerService.dispatchAllEvents(allEvents, vehicles);
            } else {
                if (lastEventCount != 0) {
                    log.info("Aucun event actif.");
                    lastEventCount = 0;
                }
                lastEventRemaining = 0;
            }

        } catch (Exception e) {
            log.error("Erreur polling : {}", e.getMessage());
        }
    }

    public List<FireDto>           getCachedFires()    { return cachedFires; }
    public List<VehicleDto>        getCachedVehicles() { return cachedVehicles; }
    public List<EmergencyEventDto> getCachedEvents()   { return cachedEvents; }
}