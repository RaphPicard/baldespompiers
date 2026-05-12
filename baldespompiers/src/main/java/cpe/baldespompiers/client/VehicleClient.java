package fr.cpe.cpefighter.client;

import com.project.model.dto.Coord;
import com.project.model.dto.VehicleDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Client pour le domaine vehicle-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /vehicles                       → tous les véhicules
 *   GET    /vehiclebyteam/{teamuuid}        → véhicules de notre équipe
 *   GET    /vehicle/{id}                   → un véhicule par id
 *   POST   /vehicle/{teamuuid}             → ajouter un véhicule
 *   PUT    /vehicle/{teamuuid}/{id}        → mettre à jour un véhicule (full update)
 *   PUT    /vehicle/move/{teamuuid}/{id}   → déplacer un véhicule
 *   DELETE /vehicle/{teamuuid}/{id}        → supprimer un véhicule
 *
 *   // profs ?
 *   DELETE /vehicle/{teamuuid}             → supprimer tous les véhicules de l'équipe
 */
@Component
public class VehicleClient {

    private final WebClient webClient;
    @Value("${simulator.token:}");
    private String token;

    public VehicleClient(WebClient simulatorWebClient) {
        this.webClient = simulatorWebClient;
    }

    public List<VehicleDto> getAllVehicles() {
        return webClient.get()
                .uri("/vehicles")
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<VehicleDto>>() {})
                .block();
    }

    public List<VehicleDto> getVehiclesByTeam(String teamUuid) {
        return webClient.get()
                .uri("/vehiclebyteam/{teamuuid}", teamUuid)
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<VehicleDto>>() {})
                .block();
    }

    public VehicleDto getVehicleById(String id) {
        return webClient.get()
                .uri("/vehicle/{id}", id)
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(VehicleDto.class)
                .block();
    }

    public VehicleDto addVehicle(String teamUuid, VehicleDto dto) {
        return webClient.post()
                .uri("/vehicle/{teamuuid}", teamUuid)
                .header("Authorization", token)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(VehicleDto.class)
                .block();
    }

    public VehicleDto updateVehicle(String teamUuid, String vehicleId, VehicleDto dto) {
        return webClient.put()
                .uri("/vehicle/{teamuuid}/{id}", teamUuid, vehicleId)
                .header("Authorization", token)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(VehicleDto.class)
                .block();
    }

    /** Appel clé pour le déplacement progressif — appelé en boucle par VehicleMovementThread */
    public VehicleDto moveVehicle(String teamUuid, String vehicleId, Coord destination) {
        return webClient.put()
                .uri("/vehicle/move/{teamuuid}/{id}", teamUuid, vehicleId)
                .header("Authorization", token)
                .bodyValue(destination)
                .retrieve()
                .bodyToMono(VehicleDto.class)
                .block();
    }

    public Boolean deleteVehicle(String teamUuid, String vehicleId) {
        return webClient.delete()
                .uri("/vehicle/{teamuuid}/{id}", teamUuid, vehicleId)
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }

    // pour les profs ?
    public Boolean deleteAllVehiclesByTeam(String teamUuid) {
        return webClient.delete()
                .uri("/vehicle/{teamuuid}", teamUuid)
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }
}