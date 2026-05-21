package cpe.baldespompiers.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

/**
 * Configuration globale de l'application : CORS et pool de threads pour les déplacements.
 *
 * Cette classe est chargée automatiquement par Spring au démarrage grâce à @Configuration.
 * Elle regroupe deux responsabilités :
 *   1. Autoriser les appels HTTP depuis le front-end (CORS)
 *   2. Définir le pool de threads utilisé pour déplacer les véhicules en parallèle
 */
@Configuration
public class AppConfig implements WebMvcConfigurer {

    // Liste des origines autorisées à appeler l'API, définie dans application.properties
    // (ex : "http://localhost:3000" pour le front-end en développement)
    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    /**
     * Configure le CORS (Cross-Origin Resource Sharing).
     *
     * Par défaut, un navigateur bloque toute requête HTTP vers un domaine différent
     * de celui de la page web. Le CORS permet d'autoriser explicitement certaines origines.
     *
     * Ici, on autorise le front-end (dont l'URL est dans application.properties) à appeler
     * toutes les routes /api/** avec les méthodes HTTP courantes.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")                                     // s'applique à toutes les routes sous /api/
                .allowedOrigins(allowedOrigins)                            // origines autorisées (lues depuis application.properties)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS"); // méthodes HTTP permises
    }

    /**
     * Crée et configure le pool de threads dédié aux déplacements de véhicules.
     *
     * Spring @Async (utilisé dans VehicleMovementThread) a besoin d'un Executor pour savoir
     * dans quel pool de threads exécuter les méthodes asynchrones.
     * Ce Bean est référencé par son nom "vehicleMovementExecutor" dans @Async("vehicleMovementExecutor").
     *
     * Paramètres du pool :
     *   - corePoolSize  : 5  → 5 threads toujours actifs (même sans tâche en cours)
     *   - maxPoolSize   : 20 → jusqu'à 20 threads simultanés si la charge est forte
     *   - queueCapacity : 50 → si les 20 threads sont occupés, jusqu'à 50 déplacements
     *                          peuvent attendre en file ; au-delà, les nouveaux sont rejetés
     *   - threadNamePrefix : préfixe visible dans les logs pour identifier ces threads
     */
    @Bean(name = "vehicleMovementExecutor")
    public Executor vehicleMovementExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);    // threads toujours actifs → pas de queue pour les 20 premiers
        executor.setMaxPoolSize(50);     // max si vraiment beaucoup de véhicules
        executor.setQueueCapacity(10);   // queue courte → force la création de threads plutôt que d'attendre
        executor.setThreadNamePrefix("vehicle-move-"); // nom des threads dans les logs (ex : vehicle-move-1)
        executor.initialize();                   // démarre le pool (obligatoire avant utilisation)
        return executor;
    }
}