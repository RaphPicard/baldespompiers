package cpe.baldespompiers.client;



/**
 * Client pour le domaine user-rest-crt.
 * Les "users" correspondent aux pompiers dans le contexte du simulateur.
 *
 * Endpoints couverts :
 *   GET    /users/              → tous les utilisateurs
 *   POST   /users/              → créer un utilisateur
 *   GET    /users/{username}    → un utilisateur par username
 *   PUT    /users/{username}    → mettre à jour un utilisateur
 *   DELETE /users/{username}    → supprimer un utilisateur
 */
@Component
public class UserClient {

    private final WebClient webClient;
    private final JwtAuthClient jwtAuthClient;

    public UserClient(WebClient simulatorWebClient, JwtAuthClient jwtAuthClient) {
        this.webClient = simulatorWebClient;
        this.jwtAuthClient = jwtAuthClient;
    }

    public List<User> getAllUsers() {
        return webClient.get()
                .uri("/users/")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<User>>() {})
                .block();
    }

    public User getUserByUsername(String username) {
        return webClient.get()
                .uri("/users/{username}", username)
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(User.class)
                .block();
    }

    public Boolean addUser(UserDto dto) {
        return webClient.post()
                .uri("/users/")
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }

    public Boolean updateUser(String username, UserDto dto) {
        return webClient.put()
                .uri("/users/{username}", username)
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }

    public Boolean deleteUser(String username) {
        return webClient.delete()
                .uri("/users/{username}", username)
                .header("Authorization", jwtAuthClient.getBearerHeader())
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }
}
