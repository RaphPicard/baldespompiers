package cpe.baldespompiers.service;



/**
 * Cerveau de l'Emergency Manager.
 *
 * Reçoit les feux/événements du EventPollerThread,
 * sélectionne les véhicules via la stratégie d'affectation,
 * et lance les déplacements via VehicleMovementThread.
 *
 * Changer @Qualifier("greedyStrategy") → @Qualifier("optimizedStrategy")
 * pour passer au Lot 3.3 sans modifier ce service.
 */

public class EmercencyManagerService {
}
