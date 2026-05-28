# Rapport de projet — CPE Fighter

**Projet :** Simulation et gestion des interventions du bal des Pompiers à Lyon  :)
**Cours :** 4ETI — Projet Java/Spring Boot 2025-2026  
**Groupe 1** — Dépôt : [gitlab.com/cpelyon/info/4eti-2025-2026-projet/groupe-1/PM](https://gitlab.com/cpelyon/info/4eti-2025-2026-projet/groupe-1/PM)  
**Date de rendu :** 28 mai 2026
**Language :** Java 21 (backend), JavaScript + Leaflet (frontend)

---

## 1. Organisation du projet

### Membres de l'équipe

| Prénom       | Rôle principal                                                                |
|--------------|-------------------------------------------------------------------------------|
| Raphaël P.   | services métier, dispatchFires, gestion liquide/carburant, stratégies         |
| Léonard      | Polling, Gestion des events (accidents/blessés), types, dispatchEvent         |
| Evan         | Frontend Leaflet, déplacement véhicules, Architecture backend, nginx          |
| Lissandre    | 3 modes de déplacement, Routage OSRM + vitesse, calcul carburant aller-retour |

### Méthode de travail

Le développement a démarré par la création de l'architecture commune et la création d'un README de projet servant de suivi entre les membres. Chacun a ensuite travaillé en parallèle sur sa partie, avec des commits réguliers via GitLab.

La gestion de projet a reposé sur :

- Un **README commun et évolutif** jouant le rôle de backlog : les fonctionnalités à faire et celles réalisées y sont tracées au fil du développement (`# FAIT :` / `# @TODO:`).
- Des **commits fréquents et ciblés** permettant de versionner chaque avancée fonctionnelle (ex. : `scoring normalisé [0,1] + dispatch optimisé`, `OSRM`, `plusieurs casernes`), parfaitement nommés pour que tous les membres puissent suivre l'avancée des autres.
- Une **résolution de conflits** fréquente, indiquant un mode de travail sur une branche `main` partagée afin d'éviter les énormes merge entre 2 branches, car il fallait que tout le monde puisse toucher un peu à tout.

### Chronologie

| Phase                  | Contenu                                                                                                      | Période estimée             |
|------------------------|--------------------------------------------------------------------------------------------------------------|-----------------------------|
| Initialisation         | Dépôt GitLab, architecture, clients HTTP, modèles DTOs/enums du proffesseur                                  |  Semaine 1 – début          |
| Fonctionnel de base    | Controllers REST (pour frontend), polling simulateur, carte Leaflet, Lot 1 & 2                               | Semaine 1 – milieu          |
| Déplacement & dispatch | Modes téléport/ligne droite/OSRM, stratégies greedy & multi-critères, Lot 3                                  | Semaine 1 – fin / Semaine 2 |
| Optimisations avancées | Carburant, liquide, équipage, repositionnement centroïde, détection feux en extinction, enchainement feu→feu | Semaine 2                   |
| Finalisation           | Frontend amélioré (liste feux, refresh 120 ms), corrections de bugs, normalisation du score                  | Semaine 2 – fin             |

---

## 2. Estimation de l'investissement

L'estimation ci-dessous se base sur l'analyse des commits (60 commits effectifs hors merges pour Raphaël, 16 pour Léonard, 14 pour Evan, 8 pour Lissandre), la taille et la complexité des contributions de chacun, et la nature des fonctionnalités portées.

| Membre     | Investissement estimé | Contributions principales                                                                                                                                                             |
|------------|-----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Raphaël P. | 25 %                  | `EmergencyManagerService`, `FireService`, stratégie de dispatch multi-critères, scoring normalisé, multi-casernes, repositionnement centroïde, chaînage feu→feu, corrections majeures |
| Léonard    | 25 %                  | `RPEventService` (accidents / blessés), gestion des types de liquide, `EventPollerThread`, corrections de threads                                                                     |
| Evan       | 25 %                  | Architecture globale, Frontend Leaflet (`map.js`, `vehicles.js`, CSS/HTML/JS), déplacement initial des véhicules, ajout d'icônes et animations, retour forcé (global + par véhicule)  |
| Lissandre  | 25 %                  | 3 modes de déplacement, Intégration OSRM (waypoints complets), vérification carburant aller-retour, `GisTools.hasFuelToReach`                                                         |

---

## 3. Architecture technique et choix technologiques

### 3.1 Vue d'ensemble en 3 couches

```
┌─────────────────────────────────────────────────────────────────┐
│  FRONTEND  (HTML / CSS / JavaScript + Leaflet)                  │
│  • map.html  vehicles.html  header.html                         │
│  • Carte Leaflet centrée sur Lyon                               │
│  • Polling GET /api/fires, /api/events, /api/vehicles (120 ms)  │
│  • Filtres par couche, liste des feux, tracés de trajets        │
└────────────────┬────────────────────────────────────────────────┘
                 │  HTTP REST (JSON)
┌────────────────▼────────────────────────────────────────────────┐
│  BACKEND  (Spring Boot   — Java 21)                              │
│                                                                  │
│  ┌─ Controllers REST ──────────────────────────────────────────┐ │
│  │  FireRestCrt · VehicleRestCrt · FacilityRestCrt             │ │
│  │  EventRestCrt                                               │ │
│  └─────────────────────────┬───────────────────────────────────┘ │
│                            │                                     │
│  ┌─ Services métier ────────▼──────────────────────────────────┐ │
│  │  EmergencyManagerService ←→ FireService ←→ RPEventService   │ │
│  │  VehicleService · FacilityService                           │ │
│  └─────────────────────────┬───────────────────────────────────┘ │
│                            │                                     │
│  ┌─ Threads ────────────────▼───────────────────────────────────┐│
│  │  EventPollerThread  (@Scheduled, toutes les 3 s)             ││
│  │    → récupère feux + events + véhicules → déclenche dispatch ││
│  │  VehicleMovementThread  (@Async, 1 thread / véhicule)        ││
│  │    → modes : teleport | straight | road (OSRM)               ││
│  └─────────────────────────┬───────────────────────────────────┘ │
│                            │                                     │
│  ┌─ Clients HTTP ───────────▼──────────────────────────────────┐ │
│  │  FireClient · VehicleClient · FacilityClient                │ │
│  │  RpEventClient · EquipmentClient                            │ │
│  └─────────────────────────┬───────────────────────────────────┘ │
└────────────────────────────┼────────────────────────────────────┘
                             │  HTTP REST
    ┌────────────────────────▼────────────────────────┐
    │  SIMULATEUR DISTANT  tp.cpe.fr:8081             │
    └─────────────────────────────────────────────────┘
```

### 3.2 Flux de dispatch (simplifié)

```
EventPollerThread (@Scheduled 3 s)
        │
        ├─ FireClient.getAllFires()      → cachedFires
        ├─ RpEventClient.getAllEvents()  → cachedEvents
        └─ VehicleClient.getByTeam()    → cachedVehicles
                │
                ▼
        EmergencyManagerService.dispatchAllFires()
                │
                ├─ FireService.dispatchFires()
                │     ├─ filtre feux (intensité, caserne en feu, extinction en cours, tri...)
                │     ├─ tri des candidats (tier 0 : fallback de mission (caserne) / tier 1 : prêts / tier 2 : acceptables)
                │     └─ dispatch(vehicle, fire)
                │             │
                │             └─ VehicleMovementThread.moveVehicle() [@Async]
                │                   ├─ mode "road" : OsrmRouterClient → waypoints
                │                   ├─ mode "straight" : interpolation pas à pas
                │                   ├─ détection feu éteint en route → FireGoneException
                │                   └─ retour caserne → recharge → feu suivant
                │
                └─ EmergencyManagerService.dispatchAllEvents()
                      └─ RPEventService.dispatchEvents()  (même logique pour les accidents)
```

### 3.3 Stratégie de scoring des véhicules

Le score d'adéquation d'un véhicule **à un feu** est calculé comme suit (valeurs normalisées dans `[0, 1]`) :

```
vehicleScore = 0.40 × vehicleNorm      (efficacité type véhicule sur l'event × taux d'équipage)
             + 0.30 × liquidScore      (compatibilité liquide / type de feu)
             + 0.20 × distanceScore    (1 / (1 + d / 0.018°) : hyperbole, ~2 km)
             + 0.07 × liquidRatio      (niveau de réservoir)
             + 0.03 × fuelRatio        (niveau de carburant)
```

Les véhicules sous les seuils minimaux de carburant/liquide (`dispatch.min.*`) sont exclus. Un véhicule sans carburant suffisant pour l'aller-retour (`GisTools.hasFuelToReach`) est également écarté.

### 3.4 Structure des sources backend

```
baldespompiers/src/main/java/cpe/baldespompiers/
├── BaldespompiersApplication.java
├── api/controller/          — 4 controllers REST (Fire, Vehicle, Facility, Event)
├── client/                  — 5 clients HTTP WebClient (Fire, Vehicle, Facility,
│                              RpEvent, Equipment)
├── config/                  — AppConfig (pool de threads), RestClientConfig (WebClient),
│                              SecurityConfig (CORS)
├── model/
│   ├── dto/                 — FireDto, VehicleDto, FacilityDto, EmergencyEventDto,
│   │                          InjuryDto, InjuredPeopleDto, Coord
│   └── type/                — FireType, VehicleType, LiquidType, EmergencyType, InjuryType
├── service/                 — EmergencyManagerService, FireService, RPEventService,
│                              VehicleService, FacilityService
├── thread/                  — EventPollerThread (@Scheduled), VehicleMovementThread (@Async)
└── tools/                   — GisTools (calculs géospatiaux : distance, carburant)
```

### 3.5 Choix technologiques

| Composant    | Technologie                               | Justification                                                                             |
|--------------|-------------------------------------------|-------------------------------------------------------------------------------------------|
| Backend      | Spring Boot 4.0.6 / Java 21               | Fourni par le sujet ; gestion native des threads asynchrones (`@Async`, `@Scheduled`)     |
| Client HTTP  | Spring WebFlux / WebClient                | Non-bloquant                                                                              |
| Routage réel | OSRM public (`router.project-osrm.org`)   | API **gratuite** pour waypoints géoréels sur Lyon ; fallback ligne droite si inaccessible |
| Frontend     | Leaflet 1.9.4 + JavaScript vanille        | Bibliothèque légère, tiles CartoDB Voyager, aucun framework imposé   ??                   |
| Proxy CORS   | NGINX (port 8082)                         | Contournement des restrictions CORS du simulateur distant                                 |
| DTOs / Enums | `fire-simulator-public` (GitLab registry) | Bibliothèque fournie par les enseignants, partagée entre équipes                          |
| Géospatial   | GeoTools 26                               | Calculs de distance et outils SIG (= calcul de carburant en fonction de la distance)      |                                  
| Build        | Maven                                     | Standard Spring Boot vu en cours                                                          |

---

## 4. Fonctionnalités réalisées

| Lot     | Description                                                                    | Statut    |
|---------|--------------------------------------------------------------------------------|-----------|
| Lot 1   | Affichage des feux et véhicules sur carte Leaflet + polling                    | Réalisé   |
| Lot 2   | CRUD casernes et véhicules, affectation manuelle depuis le front               | Réalisé   |
| Lot 3.1 | Mode téléportation                                                             | Réalisé   |
| Lot 3.2 | Déplacement en ligne droite + stratégie "gloutonne" (distance)                 | Réalisé   |
| Lot 3.3 | Routage OSRM + scoring multi-critères (liquide, équipage, distance, carburant) | Réalisé   |
| Lot 4   | Gestion du carburant, du niveau de liquide et de l'équipage dans le dispatch   | Réalisé   |

Fonctionnalités avancées également intégrées :
- détection de feux pris en charge par d'autres équipes et faire demi-tour si trop loin ou abandonné si éteint,
- repositionnement automatique au centroïde des feux actifs après charge complète si pas de feux compatibles, 
- chaînage direct feu→feu sans retour caserne si ressources suffisantes, 
- rappel d'urgence en cas de feu sur caserne si aucun véhicule disponible, 
- gestion multi-casernes, 
- Stratégie de laisser les feux à une certaine faible intensité pour faire perdre du temps aux autres équipes ("seuil remis à 0"),
- vérification de la suffisance du carburant aller-retour avant de partir.

---

## 5. Sources du projet

| Ressource                      | Lien                                                                                                                                   |
|--------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| Dépôt GitLab principal         | [gitlab.com/cpelyon/info/4eti-2025-2026-projet/groupe-1/PM](https://gitlab.com/cpelyon/info/4eti-2025-2026-projet/groupe-1/PM)         |
| Bibliothèque simulateur (DTOs) | [gitlab.com/js-project-gis-1/js-fire-simulator-public](https://gitlab.com/js-project-gis-1/js-fire-simulator-public)                   |
| API du simulateur (swagger)    | `http://tp.cpe.fr:8081/swagger-ui/index.html`                                                                                          |
| Documentation Leaflet          | [leafletjs.com](https://leafletjs.com/)                                                                                                |
| OSRM (routage)                 | [project-osrm.org](https://project-osrm.org)                                                                                           |
