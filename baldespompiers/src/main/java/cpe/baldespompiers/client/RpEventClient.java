package cpe.baldespompiers.client;

import cpe.baldespompiers.model.dto.EmergencyEventDto;
import org.springframework.beans.factory.annotation.Value;
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
 */

@Component
public class RpEventClient {
    @Value("${simulator.token:}")
    private String token;

    private final WebClient webClient;

    public RpEventClient(WebClient simulatorWebClient) {
        this.webClient = simulatorWebClient;
    }

    public List<EmergencyEventDto> getAllEvents() {
        return webClient.get()
                .uri("/rpevent")
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<EmergencyEventDto>>() {})
                .block();
    }

    public EmergencyEventDto getEventById(int id) {
        return webClient.get()
                .uri("/rpevent/{id}", id)
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(EmergencyEventDto.class)
                .block();
    }
}