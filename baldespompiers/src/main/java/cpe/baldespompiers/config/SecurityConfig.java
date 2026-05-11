package cpe.baldespompiers.config;
import org.springframework.context.annotation.*;

/**
 * Spring Security est inclus dans le pom pour sa robustesse,
 * mais l'authentification est gérée côté simulateur (JWT sortant).
 * On désactive la protection sur toutes nos routes pour ne pas
 * bloquer les appels du front-end.
 */
@Configuration
public class SecurityConfig {
}
