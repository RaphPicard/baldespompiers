package cpe.baldespompiers.thread;


/**
 * Déplacement progressif d'un véhicule via @Async.
 * Un thread par véhicule, exécuté dans vehicleMovementExecutor.
 *
 * Modes (movement.mode dans application.properties) :
 *   "teleport" → PUT direct sur destination finale
 *   "straight" → interpolation ligne droite (+50 pts, ×1)
 *   "road"     → waypoints OSRM               (+50 pts, ×2 ou ×3 avec vitesse)
 */
public class VehicleMovementThread {
}
