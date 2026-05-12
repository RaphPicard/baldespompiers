package cpe.baldespompiers.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fabrique les clients HTTP réutilisables (WebClient) pour les deux API externes.
 *
 * Un WebClient Spring est l'équivalent d'un navigateur HTTP préconfiguré :
 * on lui fixe une URL de base et des en-têtes par défaut une seule fois ici,
 * puis chaque service l'injecte et s'en sert sans répéter cette configuration.
 *
 * Deux clients sont déclarés :
 *   - simulatorWebClient → API du simulateur de feux (authentification par token requise)
 *   - osrmWebClient      → API OSRM de calcul d'itinéraires (pas d'authentification)
 */
@Configuration
public class RestClientConfig {

    /**
     * Client HTTP pour communiquer avec le simulateur de feux.
     *
     * Toutes les requêtes envoyées via ce client auront automatiquement :
     *   - l'URL de base du simulateur (définie dans application.properties)
     *   - Content-Type et Accept en JSON (format d'échange avec l'API)
     *   - un header Authorization avec le token brut de l'équipe
     *
     * Le token et l'URL sont lus depuis application.properties pour ne pas
     * les coder en dur dans le code source (sécurité, facilité de changement).
     *
     * @param baseUrl URL de base du simulateur  (ex : "https://simulateur.example.com")
     * @param token   token d'authentification fourni par le simulateur
     */
    @Bean(name = "simulatorWebClient") // appelé "simulatorWebClient" pour pouvoir l'injecter spécifiquement dans les services qui en ont besoin
    public WebClient simulatorWebClient(
            @Value("${simulator.base-url}") String baseUrl,
            @Value("${simulator.token}") String token) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")  // on envoie du JSON
                .defaultHeader("Accept", "application/json")        // on veut recevoir du JSON
                .defaultHeader("Authorization", token)              // token brut (sans préfixe Bearer)
                .build();
    }

    /**
     * Client HTTP pour interroger l'API OSRM de calcul d'itinéraires.
     *
     * OSRM est un service public basé sur OpenStreetMap qui calcule des routes routières.
     * Il ne requiert aucune authentification, d'où la configuration minimale.
     * L'URL de base est définie dans application.properties (ex : "https://router.project-osrm.org").
     *
     * @param baseUrl URL de base du serveur OSRM
     */
    @Bean(name = "osrmWebClient")
    public WebClient osrmWebClient(@Value("${osrm.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build(); // pas d'en-têtes supplémentaires : OSRM est public
    }
}