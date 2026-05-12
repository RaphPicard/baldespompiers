package cpe.baldespompiers.service;

import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.VehicleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
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
    private final Set<String> busyVehicleIds = ConcurrentHashMap.newKeySet();

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    public VehicleService(VehicleClient vehicleClient) {
        this.vehicleClient = vehicleClient;
    }

    public List<VehicleDto> getVehiclesForOurTeam() {
        return vehicleClient.getVehiclesByTeam(this.teamUuid);
    }

    public VehicleDto addVehicle(VehicleDto dto) {
        return vehicleClient.addVehicle(this.teamUuid, dto);
    }

    /**
     * Suppression sécurisée : vérifie que le véhicule est en caserne.
     * Un véhicule hors caserne supprimé = -500 points !
     */
    public Boolean deleteVehicle(String vehicleId) {
        if (busyVehicleIds.contains(vehicleId)) {
            log.warn("REFUS suppression véhicule {} — il est en mission ! Pénalité -500 pts évitée.", vehicleId);
            return false;
        }
        return vehicleClient.deleteVehicle(this.teamUuid, vehicleId);
    }
}
