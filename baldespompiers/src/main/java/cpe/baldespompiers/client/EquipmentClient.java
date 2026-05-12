package cpe.baldespompiers.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Client pour le domaine equipment-rest-crt.
 * Equipment n'existe pas dans la version actuelle de fire-simulator-public → on utilise Map<String, Object>.
 *
 * Endpoints couverts :
 *   GET  /equipment  → liste les équipements
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

    public List<Map<String, Object>> getAllEquipment() {
        return webClient.get()
                .uri("/equipment")
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
    }

    public void updateEquipment(Map<String, Object> equipment) {
        webClient.put()
                .uri("/equipment")
                .header("Authorization", token)
                .bodyValue(equipment)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public void addEquipment(Map<String, Object> equipment) {
        webClient.post()
                .uri("/equipment")
                .header("Authorization", token)
                .bodyValue(equipment)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }
}
