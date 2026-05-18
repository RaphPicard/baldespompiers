package cpe.baldespompiers.client;

import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.VehicleDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Component
public class VehicleClient {

    private final WebClient webClient;

    public VehicleClient(WebClient simulatorWebClient) {
        this.webClient = simulatorWebClient;
    }

    public List<VehicleDto> getAllVehicles() {
        return webClient.get()
                .uri("/vehicles")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<VehicleDto>>() {})
                .block();
    }

    public List<VehicleDto> getVehiclesByTeam(String teamUuid) {
        return webClient.get()
                .uri("/vehiclebyteam/{teamuuid}", teamUuid)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<VehicleDto>>() {})
                .block();
    }

    public VehicleDto getVehicleById(String id) {
        return webClient.get()
                .uri("/vehicle/{id}", id)
                .retrieve()
                .bodyToMono(VehicleDto.class)
                .block();
    }

    public VehicleDto addVehicle(String teamUuid, VehicleDto dto) {
        return webClient.post()
                .uri("/vehicle/{teamuuid}", teamUuid)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(VehicleDto.class)
                .block();
    }

    public VehicleDto updateVehicle(String teamUuid, String vehicleId, VehicleDto dto) {
        return webClient.put()
                .uri("/vehicle/{teamuuid}/{id}", teamUuid, vehicleId)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(VehicleDto.class)
                .block();
    }

    /** Appel clé pour le déplacement progressif — appelé en boucle par VehicleMovementThread */
    public VehicleDto moveVehicle(String teamUuid, String vehicleId, Coord destination) {
        return webClient.put()
                .uri("/vehicle/move/{teamuuid}/{id}", teamUuid, vehicleId)  // fix: {team uuid} → {teamuuid}
                .bodyValue(destination)
                .retrieve()
                .bodyToMono(VehicleDto.class)
                .block();
    }

    public Boolean deleteVehicle(String teamUuid, String vehicleId) {
        return webClient.delete()
                .uri("/vehicle/{teamuuid}/{id}", teamUuid, vehicleId)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }

    public Boolean deleteAllVehiclesByTeam(String teamUuid) {
        return webClient.delete()
                .uri("/vehicle/{teamuuid}", teamUuid)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }
}