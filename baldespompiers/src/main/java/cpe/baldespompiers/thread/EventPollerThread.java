package cpe.baldespompiers.thread;

import cpe.baldespompiers.client.FireClient;
import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.service.EmergencyManagerService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * Polling périodique des feux et événements.
 * Les données sont cachées pour le front-end ET transmises à EmergencyManagerService.
 */

@Component
public class EventPollerThread {

    private final FireClient fireClient;
    private final VehicleClient vehicleClient;
    private final EmergencyManagerService emergencyManagerService;

    @Value("${simulator.team.uuid}")
    private String teamUuid;

    public EventPollerThread(FireClient fireClient,
                             VehicleClient vehicleClient,
                             EmergencyManagerService emergencyManagerService) {
        this.fireClient = fireClient;
        this.vehicleClient = vehicleClient;
        this.emergencyManagerService = emergencyManagerService;
    }

    @Scheduled(fixedRate = 5000)
    public void pollAndDispatch() {
        List<FireDto> fires = fireClient.getAllFires();
        List<VehicleDto> vehicles = vehicleClient.getVehiclesByTeam(teamUuid);

        if (fires == null || fires.isEmpty()) {
            System.out.println("[Poller] Aucun feu actif.");
            return;
        }
        if (vehicles == null || vehicles.isEmpty()) {
            System.out.println("[Poller] Aucun véhicule disponible.");
            return;
        }

        System.out.println("[Poller] " + fires.size() + " feux, " + vehicles.size() + " véhicules");
        emergencyManagerService.dispatchAll(fires, vehicles);
    }
}