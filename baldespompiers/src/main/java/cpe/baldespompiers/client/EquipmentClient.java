package cpe.baldespompiers.client;

import com.project.model.dto.Equipment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
/**
 * Client pour le domaine equipment-rest-crt.
 *
 * Endpoints couverts :
 *   GET  /equipment  → liste les équipements
 *   PUT  /equipment  → mettre à jour un équipement
 *   POST /equipment  → créer un équipement
 */

public class EquipmentClient {
    private final WebClient webClient;
    private final JwtAuthClient jwtAuthClient;

    public EquipmentClient(WebClient simulatorWebClient, JwtAuthClient jwtAuthClient) {
        this.webClient = simulatorWebClient;
        this.jwtAuthClient = jwtAuthClient;
    }

    public List<Equipment> getAllEquipment() {
        return webClient.get()
                .uri("/equipment")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Equipment>>() {})
                .block();
    }

    public void updateEquipment(Equipment equipment) {
        return webclient.put()
                .uri("/equipment")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyvalue(equipment)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public void updateEquipment(Equipment equipment) {
        return webclient.post()
                .uri("/equipment")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyvalue(equipment)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }


}
