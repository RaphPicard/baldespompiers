package cpe.baldespompiers.client;



/**
 * Client pour le domaine work-session-rest-crt.
 *
 * Endpoints couverts :
 *   GET  /worksession  → createWorkSession   (boolean)
 *   POST /worksession  → createWorkSession (body: WorkSessionCreationDto)
 *   PUT  /worksession  → updateWorkSession   (body: WorkSessionUpdateDto)
 */

@Component
public class WorkSessionClient {

    private final WebClient webClient;
    private final JwtAuthClient jwtAuthClient;

    public WorkSessionClient(WebClient simulatorWebClient, JwtAuthClient jwtAuthClient) {
        this.webClient = simulatorWebClient;
        this.jwtAuthClient = jwtAuthClient;
    }

    public Boolean createSessionGet() {
        return webClient.get()
                .uri("/worksession")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }

    public Boolean createSessionPost(WorkSessionCreationDto dto) {
        return webClient.post()
                .uri("/worksession")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }

    public Boolean updateSession(WorkSessionUpdateDto dto) {
        return webClient.put()
                .uri("/worksession")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }
}