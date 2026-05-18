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

# UPDATE DE L'AVANCEMENT (11 mai)
- Création architecture + README
- Setup Maven et Spring Boot (pas fini)
- Implémentation des clients les plus faciles

# FAIT : 
- Implémenter Auth + JWT + session !!! --> Non car pour les profs
- Finir les clients --> fait
- Finir les controllers --> fait
- Finir les services --> fait

# @TODO :
- Regarder pourquoi le PUT vehicle en céer un nouveau (new id auto incrémenter)
- Pourquoi un type WATER est quand même lent sur un feu typeA ?
- 
- Faire les 3 configs (il en manque 1 : SecurityConfig.java)
- FacilityStatecache + VehicleStateCache + MissionState ??? Et donc dans les services, mettre à jour le cache à chaque appel au simulateur

- prendre en compte vitesse max pour déplacement via api OSRM (road) + chek pk des fois ca va vite

- faire le polling pour récupérer les feux et les événements (EventPollerThread) + afficher sur la carte (frontend) ?? --> evan ?

- Dispatch pas seulement pour les feux mais aussi pour les events (**road_accident & personal_injury**) 
- Prendre en compte le type d'anti-feu pour dispatch aux feux (ex : feu type A --> camion avec eau, etc.)
==> pour ça, il faut faire un mapping entre les types de feux et les types de véhicules (ex : FEU_TYPE_A --> FIRE_ENGINE, etc.)
==> Stratégie d'affectation : pour chaque feu, trouver le véhicule le plus proche qui a le bon type d'anti-feu, et l'affecter


- Prendre en compte le nombre de pompiers, l'essence qui diminue, le liquide qui diminue ... Si liquide ou essence trop faible alors préférable d'envoyer un autre véhicule
==> En fait l'essence et liquide diminue auto, moi je dois juste dispatch en fonction des ces paramètres (déjà fait ?) et choisir si attendre que le véhicule soit à 60,80,100% de fuel (resp. liquide). Donc choisis ce qui est le plus optimale et impélmentes le