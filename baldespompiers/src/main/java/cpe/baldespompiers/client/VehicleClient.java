package cpe.baldespompiers.client;

import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.VehicleDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

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

    /**
     * Variante "raw" : envoie le body tel quel (Map) au simulateur.
     * Contourne le bug de désérialisation Jackson 2/3 sur Spring Boot 4 quand on utilise @RequestBody VehicleDto.
     */
    public VehicleDto addVehicleRaw(String teamUuid, Map<String, Object> body) {
        return webClient.post()
                .uri("/vehicle/{teamuuid}", teamUuid)
                .bodyValue(body)
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

    /** Variante "raw" : Map au lieu de DTO pour contourner le bug Jackson 2/3 sur @RequestBody.
     *  Le simulateur renvoie souvent un body vide pour cet endpoint → on utilise toBodilessEntity. */
    public VehicleDto updateVehicleRaw(String teamUuid, String vehicleId, Map<String, Object> body) {
        webClient.put()
                .uri("/vehicle/{teamuuid}/{id}", teamUuid, vehicleId)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
        return null;
    }

    /** Appel clé pour le déplacement progressif — appelé en boucle par VehicleMovementThread */
    public VehicleDto moveVehicle(String teamUuid, String vehicleId, Coord destination) {
        return webClient.put()
                .uri("/vehicle/move/{teamuuid}/{id}", teamUuid, vehicleId)
                .bodyValue(destination)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 409) { //probleme de vitesse avec OSRM
                        return response.releaseBody().then(Mono.empty());
                    }
                    return response.bodyToMono(VehicleDto.class);
                })
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