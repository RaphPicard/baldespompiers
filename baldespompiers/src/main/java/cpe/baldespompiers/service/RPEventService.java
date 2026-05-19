package cpe.baldespompiers.service;

import cpe.baldespompiers.model.dto.EmergencyEventDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gestion des événements de type road_accident et personal_injury.
 * Délègue le dispatch à EmergencyManagerService.
 *
 * @TODO : implémenter la logique de dispatch pour road_accident & personal_injury
 */
@Service
public class RPEventService {

    // @TODO : injecter EmergencyManagerService + clients nécessaires

    public void dispatchEvents(List<EmergencyEventDto> events, List<VehicleDto> vehicles) {
        // @TODO : implémenter le dispatch pour road_accident & personal_injury
    }
}