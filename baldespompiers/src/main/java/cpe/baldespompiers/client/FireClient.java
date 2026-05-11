package cpe.baldespompiers.client;

/**
 * Client pour le domaine fire-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /fires                    → liste tous les feux actifs
 *   GET    /fire/{id}                → un feu par id
 *   DELETE /fires                    → supprime tous les feux
 *   DELETE /fire/{id}                → supprime un feu
 *   GET    /fire/distance            → distance entre deux coords
 *   GET    /config/creation          → FireCreationConfig
 *   PUT    /config/creation          → modifier FireCreationConfig
 *   GET    /config/behavior          → FireUpdateConfig
 *   PUT    /config/behavior          → modifier FireUpdateConfig
 */

public class FireClient {
}
