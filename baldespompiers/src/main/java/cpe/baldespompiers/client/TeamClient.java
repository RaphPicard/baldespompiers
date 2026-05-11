package cpe.baldespompiers.client;
// que pour le prof. Pour nous pas necessaire ?


/**
 * Client pour le domaine team-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /team       → toutes les équipes
 *   POST   /team       → créer une équipe
 *   PUT    /team       → mettre à jour une équipe
 *   DELETE /team       → supprimer toutes les équipes
 *   GET    /team/{id}  → une équipe par id
 *   DELETE /team/{id}  → supprimer une équipe
 */
@Component
public class TeamClient {

    private final WebClient webClient;
    private final JwtAuthClient jwtAuthClient;

    public TeamClient(WebClient simulatorWebClient, JwtAuthClient jwtAuthClient) {
        this.webClient = simulatorWebClient;
        this.jwtAuthClient = jwtAuthClient;
    }

    public List<Team> getAllTeams() {
        return webClient.get()
                .uri("/team")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Team>>() {})
                .block();
    }

    public Team getTeamById(String id) {
        return webClient.get()
                .uri("/team/{id}", id)
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Team.class)
                .block();
    }

    public Team createTeam(Team team) {
        return webClient.post()
                .uri("/team")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyValue(team)
                .retrieve()
                .bodyToMono(Team.class)
                .block();
    }

    public Team updateTeam(Team team) {
        return webClient.put()
                .uri("/team")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyValue(team)
                .retrieve()
                .bodyToMono(Team.class)
                .block();
    }

    public Boolean deleteTeamById(String id) {
        return webClient.delete()
                .uri("/team/{id}", id)
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }
}
