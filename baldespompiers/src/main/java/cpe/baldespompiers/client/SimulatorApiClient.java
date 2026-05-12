package cpe.baldespompiers.client;


/**
 * Façade centralisée vers l'API du simulateur (tp.cpe.fr:8081 ou :8083).
 *
 * Tous les appels HTTP vers le simulateur passent ICI — jamais directement
 * depuis les services. Cela facilite les tests (mock de cette classe)
 * et la bascule prod/test (changer simulator.base-url suffit).
 *
 * Les DTOs (FireDto, VehicleDto, etc.) viennent de la lib
 * js-fire-simulator-public — ne pas recréer de classes équivalentes.
 */

public class SimulatorApiClient {

}
