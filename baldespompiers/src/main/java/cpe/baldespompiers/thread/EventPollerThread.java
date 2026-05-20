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

            if (vehicles == null || vehicles.isEmpty()) {
                log.info("Aucun véhicule disponible.");
                return;
            }

            if (fires != null && !fires.isEmpty()) {
                log.info("Feux actifs : {}", fires.size());
                emergencyManagerService.dispatchAll(fires, vehicles);
            } else {
                log.info("Aucun feu actif.");
            }

            if (events != null && !events.isEmpty()) {
                log.info("Events actifs : {}", events.size());
                emergencyManagerService.dispatchAllEvents(events, vehicles);
            } else {
                log.info("Aucun event actif.");
            }

        } catch (Exception e) {
            log.error("Erreur polling : {}", e.getMessage());
        }
    }

    public List<FireDto>           getCachedFires()    { return cachedFires; }
    public List<VehicleDto>        getCachedVehicles() { return cachedVehicles; }
    public List<EmergencyEventDto> getCachedEvents()   { return cachedEvents; }
}