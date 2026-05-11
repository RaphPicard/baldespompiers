package cpe.baldespompiers.client;


/**
 * Client pour le domaine road-person-event-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /rpevent                  → tous les événements (accidents route + personnes)
 *   GET    /rpevent/{id}             → un événement par id
 *   DELETE /rpevent/{id}             → supprimer un événement
 *   DELETE /rpevent/all              → supprimer tous les événements
 *   GET    /rpevent/config/creation  → RoadPersonConfig
 *   PUT    /rpevent/config/creation  → modifier RoadPersonConfig
 */


@Component
public class RpEventClient {

    private final WebClient webClient;
    private final JwtAuthClient jwtAuthClient;

    public RpEventClient(WebClient simulatorWebClient, JwtAuthClient jwtAuthClient) {
        this.webClient = simulatorWebClient;
        this.jwtAuthClient = jwtAuthClient;
    }

    public List<EmergencyEventDto> getAllEvents() {
        return webClient.get()
                .uri("/rpevent")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<EmergencyEventDto>>() {})
                .block();
    }

    public EmergencyEventDto getEventById(int id) {
        return webClient.get()
                .uri("/rpevent/{id}", id)
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(EmergencyEventDto.class)
                .block();
    }

    public void deleteEventById(int id) {
        webClient.delete()
                .uri("/rpevent/{id}", id)
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public void deleteAllEvents() {
        webClient.delete()
                .uri("/rpevent/all")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public Object getCreationConfig() {
        return webClient.get()
                .uri("/rpevent/config/creation")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Object.class)
                .block();
    }

    public void setCreationConfig(Object config) {
        webClient.put()
                .uri("/rpevent/config/creation")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyValue(config)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }
}
