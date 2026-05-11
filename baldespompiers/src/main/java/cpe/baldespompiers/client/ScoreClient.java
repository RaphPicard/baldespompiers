package cpe.baldespompiers.client;
//c'est le prof qui gère... pas pour nous normalement (GET/PUT  /config/score)

/**
 * Client pour le domaine score-rest-crt.
 *
 * Endpoints couverts :
 *   GET /config/score  → ScoreConfig
 *   PUT /config/score  → modifier ScoreConfig
 */
@Component
public class ScoreClient {

    private final WebClient webClient;
    private final JwtAuthClient jwtAuthClient;

    public ScoreClient(WebClient simulatorWebClient, JwtAuthClient jwtAuthClient) {
        this.webClient = simulatorWebClient;
        this.jwtAuthClient = jwtAuthClient;
    }

    public Object getScoreConfig() {
        return webClient.get()
                .uri("/config/score")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Object.class)
                .block();
    }

    public void setScoreConfig(Object config) {
        webClient.put()
                .uri("/config/score")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyValue(config)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }
}
