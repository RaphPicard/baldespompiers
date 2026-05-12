package cpe.baldespompiers.client;

import cpe.baldespompiers.model.dto.EmergencyEventDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Client pour le domaine road-person-event-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /rpevent                  → tous les événements (accidents route + personnes)
 *   GET    /rpevent/{id}             → un événement par id
 *
 * Le header Authorization est ajouté automatiquement par simulatorWebClient (voir RestClientConfig).
 */
@Component
public class RpEventClient {

    private final WebClient webClient;

    public RpEventClient(WebClient simulatorWebClient) {
        this.webClient = simulatorWebClient;
    }

    public List<EmergencyEventDto> getAllEvents() {
        return webClient.get()
                .uri("/rpevent")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<EmergencyEventDto>>() {})
                .block();
    }

    public EmergencyEventDto getEventById(int id) {
        return webClient.get()
                .uri("/rpevent/{id}", id)
                .retrieve()
                .bodyToMono(EmergencyEventDto.class)
                .block();
    }
}