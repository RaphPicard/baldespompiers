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

    private volatile List<FireDto>           cachedFires    = List.of();
    private volatile List<VehicleDto>        cachedVehicles = List.of();
    private volatile List<EmergencyEventDto> cachedEvents   = List.of();

    private volatile int  lastFireCount       = -1;
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
            List<VehicleDto>        vehicles = vehicleClient.getVehiclesByTeam(teamUuid); //ne prend que les dispos

            if (fires    != null) this.cachedFires    = fires;
            if (events   != null) this.cachedEvents   = events;
            if (vehicles != null) this.cachedVehicles = vehicles;

            if (fires != null && !fires.isEmpty()) {
                int fireCount = fires.size();
                if (fireCount != lastFireCount) {
                    log.info("Feux actifs : {}", fireCount);
                    lastFireCount = fireCount;
                }
                emergencyManagerService.dispatchAll(fires, vehicles);
            } else {
                if (lastFireCount != 0) {
                    log.info("Aucun feu actif.");
                    lastFireCount = 0;
                }
            }

            List<EmergencyEventDto> allEvents = new ArrayList<>(events != null ? events : List.of());

// Ajouter les blessés des feux comme faux events
            if (fires != null) {
                fires.stream()
                        .filter(f -> f.getInjuredPeopleDtoList() != null && !f.getInjuredPeopleDtoList().isEmpty())
                        .forEach(f -> {
                            EmergencyEventDto e = new EmergencyEventDto();
                            e.setId(-f.getId());
                            e.setEventType(EmergencyType.PERSONAL_INJURY);
                            e.setIntensity(0f);
                            e.setLon(f.getLon());
                            e.setLat(f.getLat());
                            e.setInjuredPeopleDtoList(f.getInjuredPeopleDtoList());
                            allEvents.add(e);
                        });
            }

            if (!allEvents.isEmpty()) {
                int eventCount = allEvents.size();
                if (eventCount != lastEventCount) {
                    log.info("Events actifs (dont blessés sur feux) : {}", eventCount);
                    lastEventCount = eventCount;
                }
                long remaining = allEvents.stream()
                        .filter(ev -> ev.getInjuredPeopleDtoList() != null)
                        .flatMap(ev -> ev.getInjuredPeopleDtoList().stream())
                        .filter(p -> p.getInjuryDto() != null && p.getInjuryDto().getIntensity() > 0)
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