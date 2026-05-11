package cpe.baldespompiers.client;

/**
 * Client pour le domaine facility-rest-crt.
 *
 * Endpoints couverts :
 *   GET    /facility               → toutes les casernes (+ query param optionnel teamcode)
 *   POST   /facility               → créer une caserne
 *   DELETE /facility               → supprimer toutes les casernes
 *   GET    /facility/{id}          → une caserne par id
 *   DELETE /facility/{id}          → supprimer une caserne
 *   GET    /facility/object/{id}   → description géométrique (Facility, pas FacilityDto)
 */

public class FacilityClient {
}
