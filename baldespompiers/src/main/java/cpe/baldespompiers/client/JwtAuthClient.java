package cpe.baldespompiers.client;

/**
 * Gère uniquement l'authentification JWT (POST /login).
 * Le token obtenu est utilisé par tous les autres clients.
 *
 * NB : POST /login attend un body de type JwtRequest (username + password).
 * La réponse est un objet dont on extrait le token — la clé exacte
 * dépend de l'implémentation serveur (probablement "token" ou "jwtToken").
 * TODO : vérifier la clé de réponse en testant contre :8083.
 */


public class JwtAuthClient {
}
