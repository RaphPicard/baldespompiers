package cpe.baldespompiers.client;


/**
 * Client pour le domaine vehicle-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /vehicles                       → tous les véhicules
 *   GET    /vehiclebyteam/{teamuuid}        → véhicules de notre équipe
 *   GET    /vehicle/{id}                   → un véhicule par id
 *   POST   /vehicle/{teamuuid}             → ajouter un véhicule
 *   PUT    /vehicle/{teamuuid}/{id}        → mettre à jour un véhicule (full update) --> ses coords !
 *   PUT    /vehicle/move/{teamuuid}/{id}   → déplacer un véhicule
 *   DELETE /vehicle/{teamuuid}/{id}        → supprimer un véhicule
 *   DELETE /vehicle/{teamuuid}             → supprimer tous les véhicules de l'équipe
 */
public class VehicleClient {
}
