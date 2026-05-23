# CPE Fighter — Emergency Manager

**Simulation, gestion et optimisation des interventions des services d'urgence à Lyon.**

Système web temps réel pour affecter des véhicules d'urgence aux incendies et accidents, maximisant les points de score en fonction des stratégies implémentées.

---

## 🎯 Vision

- **Backoffice :** Spring Boot monolithe qui communique avec le simulateur distant `tp.cpe.fr:8081/8083`
- **Frontend :** Leaflet (carte interactive) affichant feux, véhicules, casernes en temps réel
- **Score :** Points gagnés selon efficacité (distance, carburant, équipage, routage)
- **Scope :** Lot 1–3 en 28h (Lot 4 = extensions optionnelles)

---

## 📊 Architecture en 3 couches

```
Frontend (Leaflet)
    ↓ GET /api/fires, POST /api/vehicles, etc.
    ↓
Controllers REST ← ← expose notre API
    ↓
Services + Clients ← ← logique métier + appels au simulateur
    ↓
tp.cpe.fr:8083 ← ← simulateur (polling, dispatch)
```

**Controllers** = API pour le frontend.
**Clients** = appels au simulateur externe.
**Services** = logique métier (affectation, cache, score).

### Flux détaillé des données

```
Simulateur (tp.cpe.fr:8083)
        ↑↓ HTTP
[FireClient]  [VehicleClient]  [RpEventClient]  [FacilityClient]
         ↘          ↓
      [EventPollerThread]  ←  @Scheduled (toutes les 5s)
           ↙              ↘
   cachedFires/Vehicles    [EmergencyManagerService.dispatchAll()]
           ↓                         ↓  (utilise les Types pour décider)
   [FireRestCrt]           [VehicleMovementThread]
       ↓                         ↓
  GET /api/fires         [VehicleClient.moveVehicle()]
       ↓                         ↓
    [Front]               Simulateur (déplacement)
```

- Les **Clients** sont les seuls à faire des appels HTTP vers le simulateur.
- **EventPollerThread** orchestre le timing : il récupère via les clients, met en cache, puis déclenche `EmergencyManagerService`.
- Les **Controllers** lisent le cache du thread (pas les clients directement) → pas de requête simulateur à chaque appel front.
- Les **DTOs** (`FireDto`, `VehicleDto`…) transitent sans transformation du simulateur jusqu'au front.
- Les **Types** (enums `FireType`, `VehicleType`…) portent la sémantique métier utilisée dans `EmergencyManagerService` pour filtrer et scorer.

---

## 🔄 Flux complet : créer et dispatcher un véhicule

### 1️⃣ Front-end clique "Ajouter camion"
```javascript
fetch('/api/vehicles', {
    method: 'POST',
    body: JSON.stringify({type: 'FIRE_ENGINE', facilityId: 'caserne-1'})
})
```

### 2️⃣ VehicleController reçoit et délègue
```java
@PostMapping
public VehicleDto addVehicle(@RequestBody VehicleDto dto) {
    return vehicleService.addVehicle(dto);
}
```

### 3️⃣ VehicleService valide + appelle le simulateur
```java
public VehicleDto addVehicle(VehicleDto dto) {
    // Validation
    VehicleDto created = vehicleClient.addVehicle(teamUuid, dto);
    // Stocke en cache local
    stateCache.put(created.getId(), new VehicleStateCache());
    return created;
}
```

### 4️⃣ VehicleClient appelle POST /vehicle/{teamuuid}
```java
public VehicleDto addVehicle(String teamUuid, VehicleDto dto) {
    return webClient.post()
        .uri("/vehicle/{teamuuid}", teamUuid)
        .bodyValue(dto)
        .retrieve()
        .bodyToMono(VehicleDto.class)
        .block();
}
```

### 5️⃣ Simulateur retourne le véhicule créé
→ Cache local mis à jour
→ Controller retourne au frontend
→ Frontend affiche le marqueur sur la carte

---

## 📦 Structure du projet

```
cpefighter/
├── pom.xml
├── src/main/java/fr/cpe/cpefighter/
│   ├── CpeFighterApplication.java
│   ├── api/controller/          ← ← endpoints pour le front (7 controllers)
│   ├── service/                 ← ← logique métier (6 services + 2 stratégies d'affectation)
│   ├── client/                  ← ← appels au simulateur (10 clients)
│   ├── config/                  ← ← WebClient, CORS, Security
│   ├── model/                   ← ← état interne (MissionState, VehicleStateCache, etc.)
│   └── thread/                  ← ← polling + déplacement (@Scheduled, @Async)
├── src/main/resources/
│   └── application.properties
└── README.md
```

---

## 🎓 Les 3 lots à faire

| Lot | Quoi | Points | Facteur |
|-----|------|--------|--------|
| **Lot 1** | Affichage feux/véhicules sur carte Leaflet + polling | Base | ×1 |
| **Lot 2** | CRUD casernes + véhicules, affectation manuelle | +50 | ×1 |
| **Lot 3.1** | Téléportation + affectation gloutonne (distance) | +50 | ×2 |
| **Lot 3.2** | Déplacement ligne droite + greedy assignment | +50 | ×2 |
| **Lot 3.3** | Routage OSRM + optimisation multi-critères | +50 | ×3 |
| **Lot 4** | Carburant, extincteur, équipage, fatigue | +50 each | Variante |

---

## 👥 Répartition des tâches (4 ingénieurs)

### 👨‍💼 Ingénieur 1 — Backend Core & API

**Responsabilités :**
- Setup Maven + configurations (AppConfig, SecurityConfig, RestClientConfig)
- Implémenter WorkSessionService + init JWT au démarrage
- Tous les **Clients** (10 fichiers) — garantir 100% fidèle au swagger
- EmergencyManagerService + VehicleService + FacilityService
- Tous les **Controllers** (7 fichiers) — GET/POST/DELETE REST
- Tests unitaires pour les services critiques

**Délai :** ~12h (core setup 2h, clients 4h, services 3h, controllers 2h, tests 1h)

---

### 🗺️ Ingénieur 2 — Frontend Leaflet

**Responsabilités :**
- HTML/CSS/JS Leaflet : carte interactive de Lyon
- Polling `GET /api/fires` et `GET /api/events` → affichage dynamique des marqueurs
- Panneau détails : clique sur un feu → affiche intensité, étendue, position
- Filtres : par type, intensité, etc.
- Tableau score en temps réel (polling `/api/score`)
- Responsive design (mobile OK)

**Délai :** ~10h (setup Leaflet 2h, affichage feux 2h, interactions 2h, filtres 1h, polish 2h, tests 1h)

---

### 🚗 Ingénieur 3 — Dispatch & Déplacement (Lot 3)

**Responsabilités :**
- VehicleMovementThread (@Async) : implémente les 3 modes (teleport, straight, road)
- GreedyAssignmentStrategy : distance euclidienne, sélectionne le plus proche
- OptimizedAssignmentStrategy : scoring multi-critères
- Test des déplacements progressifs (évite les overlap)
- OsrmRouterClient pour le routage réel (OSRM API)
- Calcul du nombre d'étapes en fonction de la vitesse du véhicule

**Délai :** ~14h (VehicleMovementThread 3h, stratégies 3h, OSRM 2h, tests 3h, tuning 2h, buffer 1h)

---

### 📊 Ingénieur 4 — UI Admin + Lot 2 (Casernes, Score)

**Responsabilités :**
- Interface d'administration : CRUD casernes (form modal ou panneau)
- CRUD véhicules : bouton ajouter/supprimer/modifier
- Affectation manuelle : drag-drop ou sélecteur à cliquer (avant Lot 3)
- ScoreService + ScoreController : lire le score simulateur, l'exposer
- Intégration UserService/TeamService (gestion pompiers)
- FacilityService complet : synchronisation cache ↔ simulateur

**Délai :** ~9h (UI casernes 2h, UI véhicules 2h, affectation manuelle 1h, score 1h, sync cache 2h, tests 1h)

---

## 🚀 Timeline proposée (28h)

| Semaine | Jour | Ing.1 | Ing.2 | Ing.3 | Ing.4 |
|---------|------|-------|-------|-------|-------|
| **Sem 1** | Lun–Mar | Setup Maven + Clients (4h) | Leaflet map (3h) | VehicleMovementThread (2h) | UI casernes (2h) |
| | Mer–Jeu | Services + Controllers (4h) | Polling feux (3h) | Stratégies greedy (3h) | UI véhicules (2h) |
| | Ven | Tests + buffer (2h) | Filtres (2h) | OSRM (2h) | Affectation manuelle (1h) |
| **Sem 2** | Lun–Mar | Deploy + doc (2h) | Polish frontend (2h) | Tuning déplacement (2h) | Score + sync (2h) |
| | Mer | Slack buffer pour bugs (4h d'équipe) | | | |
| | Jeu–Ven | Démo + soutenance | | | |

---

## 🔑 Checklist du démarrage

- [ ] Créer dépôt Git, token GitLab pour la lib simulateur
- [ ] Chacun : `mvn clean install && mvn spring-boot:run` → `http://localhost:8080` doit répondre
- [ ] Ing.1 : WorkSessionService.init() doit faire login() et afficher le token
- [ ] Ing.2 : Leaflet doit charger, centré sur Lyon, sans erreur CORS
- [ ] Ing.3 : VehicleMovementThread compile et s'exécute en @Async
- [ ] Ing.4 : Un endpoint `/api/score` doit répondre avec un objet JSON

---

## 📝 Notes d'implémentation

**Secrets statiques** : pas de `.gitignore` pour les credentials en dev, mais en prod → env vars `SIMULATOR_USERNAME`, `SIMULATOR_PASSWORD`.

**Polling** : `@Scheduled(fixedDelayString = "${poller.interval-ms:3000}")` → chaque 3s, EventPollerThread appelle `FireClient.getAllFires()` et cache le résultat. Les Controllers lisent le cache, pas le simulateur.

**JWT** : `JwtAuthClient.login()` au démarrage (@PostConstruct), stocke le token, tous les clients l'injectent via `getBearerHeader()`.

**Cache état** : `ConcurrentHashMap<String, VehicleStateCache>` local → sera Redis au Lot 4 (microservices). Pour l'instant, c'est in-memory.

**Affectation** : `@Qualifier("greedyStrategy")` dans `EmergencyManagerService`. Bascule vers `"optimizedStrategy"` pour Lot 3.3 sans modifier le service.

---

## 🎬 Avant la soutenance (29 mai)

- ✅ Démo live : affichage feux → dispatch automatique → véhicule se déplace → score augmente
- ✅ Tous les logs sans erreur → `mvn clean test`
- ✅ Interface admin : créer une caserne, ajouter un véhicule, le voir sur la carte
- ✅ Score affiché en temps réel sur un tableau de bord

---

## 📚 Ressources

- Swagger API : `http://localhost:8080/swagger-ui.html` (une fois configuré)
- Simulateur test : `http://tp.cpe.fr:8083/swagger-ui/index.html`
- Lib externe (DTOs/Enums) : `https://gitlab.com/js-project-gis-1/js-fire-simulator-public`
- Leaflet docs : `https://leafletjs.com/`
- Mapbox (routage) : `https://docs.mapbox.com/api/navigation/directions/`



---
---

# FAIT :
- Implémenter Auth + JWT + session !!! --> Non car pour les profs
- Finir les clients --> fait
- Finir les controllers --> fait
- Finir les services --> fait
- **FAIT** : `isLiquidCompatible(liquidType, fireType)` filtre les véhicules incompatibles via la matrice d'efficacité de `LiquidType` ; `vehicleScore` intègre l'efficiency (×50) en plus de l'équipage (×10) et des ressources.
- **FAIT** : stratégie à deux seuils dans `EmergencyManagerService.dispatchAll` :
    - **Tier 1** (`best_candidates`) : fuel ≥ `dispatch.ready.fuel` ET liquid ≥ `dispatch.ready.liquid` ET compatible → meilleur score
    - **Tier 2** (`candidates`) : fallback si aucun véhicule "prêt" (seuils minimaux `dispatch.min.*`) pour ne pas laisser le feu s'étendre
    - Véhicules sous le seuil minimum ou liquide incompatible : jamais dispatchés
    - Seuils configurables dans `application.properties`
- **FAIT** : vitesse max prise en compte pour le déplacement — `computeStepDelay` calcule le délai entre chaque pas proportionnellement à `VehicleType.getMaxSpeed()` (référence 110 km/h)
- **FAIT** : véhicule rentre/recharge à la caserne uniquement si nécessaire — `vehicleNeedsRecharge` vérifie fuel < minFuel ou liquid < minLiquid ; `waitForRecharge` attend les seuils `readyFuel`/`readyLiquid` avant de libérer le véhicule
- **FAIT** : si le véhicule n'arrive pas au feu par la route (feu en zone inaccessible…), fallback automatique en ligne droite — les 3 cas d'échec OSRM (HTTP error, code invalide, pas de coordonnées) tombent sur `moveToPoint` ; le tronçon final hors-route est aussi parcouru en ligne droite
- **FAIT** : support multi-casernes dans `FireService` — `knownFacilities` remplace les scalaires `caserneLon`/`caserneLat` ; `ensureFacilityList()` charge toutes les casernes ; `caserneOnFire()` détecte un feu sur n'importe laquelle ; `handleCasernefire()` rappelle le véhicule le plus proche de LA caserne concernée (et non d'une caserne unique hardcodée)
- **FAIT** : rappel global (`recall-all`) rapatrie aussi les véhicules inactifs — `recallIdleVehicle()` dans `VehicleMovementThread` envoie chaque véhicule sans thread actif vers sa caserne (`facilityRefID`) ; s'annule proprement si le rappel est désactivé en cours de route
- **FAIT** : faire en sorte que les vehicules captent si le feu sur lequel il est dispatché a été eteint entre temps sur la route (par d'autres equipes) → demi-tour vers un autre feu si possible via `FireGoneException` + `redirectAfterFireGone`
- **FAIT** : distance prise en compte dans le score via `distanceWeight` (×300) dans `vehicleScore` — un véhicule proche est favorisé, ce qui couvre implicitement les waypoints OSRM (plus de waypoints = plus de distance = score plus bas)
- **FAIT** : dispatch pour les events (`road_accident` & `personal_injury`) — `RPEventService.dispatchEvents()` score et affecte les véhicules aux événements ; `moveVehicleToEvent()` gère le déplacement, l'attente de résolution, la redirection si l'event est résolu en route (`redirectAfterEventGone`) et le retour caserne si ressources insuffisantes
- Pour le moment, quand un véhicule a éteint un feu et qu'aucun autre feu ne lui correspond, il est en **retour libre** et rentre à la caserne → à changer ?
- **FAIT** : stratégie abandon feux faibles — `dispatchFires` filtre les feux avec `intensité ≤ abandonIntensity + 2` (marge de +2 pour éviter de retourner en boucle sur un feu quasi-éteint) ; les feux sur caserne ignorent ce seuil (`threshold = 0`)
- **FAIT** : vérification carburant aller-retour avant dispatch — `GisTools.hasFuelToReach(vehicle, fireLon, fireLat, facilityLon, facilityLat)` calcule si le véhicule a assez de carburant pour aller au feu ET revenir à la caserne ; utilisé dans `candidates()` de `FireService` et dans `RPEventService`
- **FAIT** : logs non répétitifs — `waitForFireOut` et `waitForEventOut` ne loggent l'intensité que lorsqu'elle change (variable locale `lastIntensity: float`) ; `waitForRecharge` idem pour fuel/liquid ; nombre de blessés restants centralisé dans `EventPollerThread` via `lastEventRemaining` (même pattern que `lastFireCount`)
- **FAIT** : frontend rafraîchissement automatique toutes les 3 s avec interface véhicules améliorée (vehicles.html + vehicles.js refactorisés)
- **FAIT** : pool de threads corrigé dans `AppConfig` — nombre de threads `vehicleMovementExecutor` ajusté pour éviter les blocages lors des dispatches simultanés

# @TODO :
- Faire les 3 configs (il en manque 1 : SecurityConfig.java)
- FacilityStatecache + VehicleStateCache + MissionState ??? Et donc dans les services, mettre à jour le cache à chaque appel au simulateur
- Changement de liquide automatiquement à la caserne si un véhicule n'a pas de feu de son type de liquide à eteindre, pour éviter qu'il attente à la caserne alors qu'il pourrait être utile sur un feu d'un autre type (ex : un véhicule à eau qui attend alors qu'il pourrait aller éteindre un feu de type électrique en changeant de liquide à la caserne)
- Optimiser le retour à la caserne la plus proche (et pas forcément la caserne d'origine)
- Calculer combien de liquid et de fuel il faut pour eteindre x feu, et calculer minFuel et minLiquid en fonction de la distance au feu et de l'intensité du feu (pour éviter les retours à la caserne inutiles)
- Prendre en compte la distance et la conso par km pour chaque véhicule pour savoir quand on doit rentrer à la caserne en fonction de la distance et du fuel. Pour savoir s'il doit faire le plein avant de partir ou pas
- Seuils d'abandon de mission, de recharge, de dispatch à revoir en terme de RATIO (pour que chaque vehicule soit adapaté)
- Rappel forcé si feu caserne → rappelle parfois une ambulance au lieu d'un camion avec le bon anti-feu

-Pourquoi il y a un fire engine special powder qui aille sur un road accident avec 4 injured people --> Si toutes les ambulances occupées alors il y va un peu pour rien. c'est bien parce qu'il a rien à faire mais si ya un feu electrique qui spawn ba il sera occupé pour rien !

- @Todo : détecter si un feu diminue ou pas en live (équipe dessus) et PRIORISER ceux qui ne diminuent pas
- quand un véhicule est chargé, si il n'est pas dispatché, l’envoyer au milieu de la zone de travail, vers le CENTROÏDE DES FEUX ACTIFS plutôt que de rester à la caserne. Ou vu qu’on a 2 casernes, les faire se placer de manière équilibrée