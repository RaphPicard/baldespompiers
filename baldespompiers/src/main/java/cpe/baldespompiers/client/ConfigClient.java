package cpe.baldespompiers.client;
// pas utile pour nous ? pour le prof ?

/**
 * Client regroupant les configs simulateur secondaires.
 *
 * Endpoints couverts :
 *   GET/PUT /config/gfconfig                  → GeneralFacilityConfig
 *   GET/PUT /injury/config/creation           → InjuryCreationConfig
 *   GET/PUT /injury/config/update             → InjuryUpdateConfig
 *   GET/PUT /emergency_event/config/creation  → EmergencyEventCreationConfig
 *
 * Ces configs sont utiles principalement sur le serveur de test :8083
 * pour tuner la fréquence et l'intensité des événements générés.
 * En prod (:8081) elles sont partagées entre toutes les équipes.
 */

public class ConfigClient {
    @Value("${simulator.token:}");
    private String token;
}
