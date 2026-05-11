package cpe.baldespompiers.service;


/**
 * Gère le cycle de vie de la session de jeu.
 *
 * Séquence de démarrage (appelée automatiquement via @PostConstruct) :
 *   1. POST /login          → obtenir le token JWT
 *   2. GET  /worksession    → créer/rejoindre la session de jeu
 *
 * Le teamUuid est stocké ici et injecté dans tous les services qui en ont besoin.
 *
 * TODO : selon ce que retourne GET /worksession, peut-être utiliser
 *        POST /worksession avec WorkSessionCreationDto à la place.
 *        À tester sur :8083 en premier.
 */
public class WorkSessionService {
}
