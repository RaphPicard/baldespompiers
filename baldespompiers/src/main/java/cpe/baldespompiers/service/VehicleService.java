package cpe.baldespompiers.service;

import com.project.model.dto.VehicleDto;
import fr.cpe.cpefighter.client.VehicleClient;
import fr.cpe.cpefighter.model.VehicleStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logique métier autour des véhicules.
 *
 * Responsabilités :
 *   - CRUD véhicules (délègue à VehicleClient)
 *   - Maintien du cache d'état local (carburant, liquide, statut occupé)
 *   - Vérification qu'un véhicule est en caserne avant suppression (pénalité -500 pts !)
 */
@Service
public class VehicleService {

    private static final Logger log = LoggerFactory.getLogger(VehicleService.class);

    private final VehicleClient vehicleClient;
    @Value("${simulator.team.uuid}")
    private String teamUuid;


    public VehicleService(VehicleClient vehicleClient) {
        this.vehicleClient = vehicleClient;
    }

    public List<VehicleDto> getVehiclesForOurTeam() {
        return vehicleClient.getVehiclesByTeam(this.teamUuid);
    }

    public VehicleDto addVehicle(VehicleDto dto) {
        VehicleDto created = vehicleClient.addVehicle(this.teamUuid, dto);
        //if (created != null) initCacheForVehicle(created);
        return created;
    }

    /**
     * Suppression sécurisée : vérifie que le véhicule est en caserne.
     * Un véhicule hors caserne supprimé = -500 points !
     */
    public Boolean deleteVehicle(String vehicleId) {
        VehicleStateCache state = stateCache.get(vehicleId);
        if (state != null && state.isBusy()) {
            log.warn("REFUS suppression véhicule {} — il est en mission ! Pénalité -500 pts évitée.", vehicleId);
            return false;
        }
        stateCache.remove(vehicleId);
        return vehicleClient.deleteVehicle(this.teamUuid, vehicleId);
    }
}