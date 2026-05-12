package cpe.baldespompiers.client;

import com.project.model.dto.FireDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client pour le domaine fire-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /fires                    → liste tous les feux actifs
 *   GET    /fire/{id}                → un feu par id
 *   GET    /fire/distance            → distance entre deux coords
 */
@Component
public class FireClient {

    @Value("${simulator.token:}");
    private String token;

    private final WebClient webClient;

    public FireClient(WebClient simulatorWebClient) {
        this.webClient = simulatorWebClient;
    }

    public List<FireDto> getAllFires() {
        return webClient.get()
                .uri("/fires")
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<FireDto>>() {})
                .block();
    }

    public FireDto getFireById(int id) {
        return webClient.get()
                .uri("/fire/{id}", id)
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(FireDto.class)
                .block();
    }

    /**
     * Calcul de distance entre deux coords via le simulateur.
     * Utile pour l'algorithme d'affectation (évite de recalculer côté back).
     * Retourne une distance en mètres (int).
     */
    public Integer getDistance(double lon1, double lat1, double lon2, double lat2) {
        return webClient.get()
                .uri(u -> u.path("/fire/distance")
                        .queryParam("lonCoord1", lon1)
                        .queryParam("latCoord1", lat1)
                        .queryParam("lonCoord2", lon2)
                        .queryParam("latCoord2", lat2)
                        .build())
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Integer.class)
                .block();
    }
}