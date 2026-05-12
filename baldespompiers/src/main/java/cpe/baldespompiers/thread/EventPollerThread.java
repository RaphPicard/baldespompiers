package cpe.baldespompiers.thread;

import cpe.baldespompiers.client.FireClient;
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

/**
 * Polling périodique des feux et véhicules. // @TODO : polling évents !!!
 * Les données sont cachées pour le front-end ET transmises à EmergencyManagerService.
 */
@Component
public class EventPollerThread {

    private static final Logger log = LoggerFactory.getLogger(EventPollerThread.class);

    private final FireClient fireClient;
    private final VehicleClient vehicleClient;
    private final EmergencyManagerService emergencyManagerService;

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    private volatile List<FireDto> cachedFires = List.of();
    private volatile List<VehicleDto> cachedVehicles = List.of();

    public EventPollerThread(FireClient fireClient,
                             VehicleClient vehicleClient,
                             EmergencyManagerService emergencyManagerService) {
        this.fireClient = fireClient;
        this.vehicleClient = vehicleClient;
        this.emergencyManagerService = emergencyManagerService;
    }

    @Scheduled(fixedDelayString = "${poller.interval-ms:5000}") // fixedDelay = attendre que  l'exec soit finie et attendre le delay
    // fixedRate = toutes les 5 secondes
    public void pollAndDispatch() {
        try {
            List<FireDto> fires = fireClient.getAllFires();
            List<VehicleDto> vehicles = vehicleClient.getVehiclesByTeam(teamUuid);

            if (fires != null) this.cachedFires = fires;
            if (vehicles != null) this.cachedVehicles = vehicles;

            if (fires == null || fires.isEmpty()) {
                log.info("Aucun feu actif.");
                return;
            }
            if (vehicles == null || vehicles.isEmpty()) {
                log.info("Aucun véhicule disponible.");
                return;
            }

            log.info("Feux actifs : {}, véhicules : {}", fires.size(), vehicles.size());
            emergencyManagerService.dispatchAll(fires, vehicles);
        } catch (Exception e) {
            log.error("Erreur polling : {}", e.getMessage());
        }
    }

    public List<FireDto> getCachedFires() { return cachedFires; }
    public List<VehicleDto> getCachedVehicles() { return cachedVehicles; }
    // @TODO : implémenter le polling events (RpEventClient) — stub pour compilation
    public List<EmergencyEventDto> getCachedEvents() { return List.of(); }
}