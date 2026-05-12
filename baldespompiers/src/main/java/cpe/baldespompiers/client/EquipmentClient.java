package cpe.baldespompiers.client;

import com.project.model.dto.Equipment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Client pour le domaine equipment-rest-crt.
 *
 * Endpoints couverts :
 *   GET  /equipment  → liste les équipements
 *
 *   // utile que pour le prof ?
 *   PUT  /equipment  → mettre à jour un équipement
 *   POST /equipment  → créer un équipement
 */

@Component
public class EquipmentClient {
    private final WebClient webClient;
    @Value("${simulator.token:}")
    private String token;

    public EquipmentClient(WebClient simulatorWebClient) {
        this.webClient = simulatorWebClient;
    }

    public List<Equipment> getAllEquipment() {
        return webClient.get()
                .uri("/equipment")
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Equipment>>() {})
                .block();
    }

    public void updateEquipment(Equipment equipment) {
        webClient.put()
                .uri("/equipment")
                .header("Authorization", token)
                .bodyValue(equipment)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public void addEquipment(Equipment equipment) {
        webClient.post()
                .uri("/equipment")
                .header("Authorization", token)
                .bodyValue(equipment)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }
}