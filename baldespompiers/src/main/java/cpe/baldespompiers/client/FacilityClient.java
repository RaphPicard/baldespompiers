package cpe.baldespompiers.client;

import cpe.baldespompiers.model.dto.FacilityDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Client pour le domaine facility-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /facility               → toutes les casernes (+ query param optionnel teamcode)
 *   GET    /facility/{id}          → une caserne par id
 *   GET    /facility/object/{id}   → description géométrique (Facility, pas FacilityDto)
 *   autres : pour profs.
 */

@Component
public class FacilityClient {

    private final WebClient webClient;
    @Value("${simulator.token:}")
    private String token;

    public FacilityClient(WebClient simulatorWebClient) {
        this.webClient = simulatorWebClient;
    }

    public List<FacilityDto> getAllFacilities(String teamCode) {
        return webClient.get()
                .uri(u -> {
                    var builder = u.path("/facility");
                    if (teamCode != null) builder.queryParam("teamcode", teamCode);
                    return builder.build();
                })
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<FacilityDto>>() {})
                .block();
    }

    public FacilityDto getFacilityById(String id) {
        return webClient.get()
                .uri("/facility/{id}", id)
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(FacilityDto.class)
                .block();
    }

    /** Retourne l'objet géométrique brut (Map) — Facility n'existe pas encore dans la lib partagée */
    public Object getFacilityObject(String id) {
        return webClient.get()
                .uri("/facility/object/{id}", id)
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(Object.class)
                .block();
    }
}