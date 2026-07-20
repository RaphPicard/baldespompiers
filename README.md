# CPE Fighter — Lyon Emergency Manager 2026

**Simulation, gestion et optimisation des interventions des services d'urgence à Lyon.**

Système web temps réel pour affecter des véhicules d'urgence aux incendies et accidents, maximisant les points de score en fonction des stratégies implémentées.

---
## Contributors from CPE LYON

- Evan Dupas-Benhamou
- Lissandre Laforet
- Raphael Picard
- Leonard Rivaud
  
---

## Architecture

```
Frontend (Leaflet)
    ↓ GET /api/fires, POST /api/vehicles, etc.
    ↓
Controllers REST
    ↓
Services + Clients
    ↓
tp.cpe.fr:8083  (simulateur)
```

```
Simulateur (tp.cpe.fr:8083)
        ↑↓ HTTP
[FireClient]  [VehicleClient]  [RpEventClient]  [FacilityClient]
         ↘          ↓
      [EventPollerThread]  ←  @Scheduled (toutes les 5s)
           ↙              ↘
   cachedFires/Vehicles    [EmergencyManagerService.dispatchAll()]
           ↓                         ↓
   [FireRestCrt]           [VehicleMovementThread]
       ↓                         ↓
  GET /api/fires         [VehicleClient.moveVehicle()]
       ↓                         ↓
    [Front]               Simulateur (déplacement)
```

- Les **Clients** sont les seuls à faire des appels HTTP vers le simulateur.
- **EventPollerThread** orchestre le timing : récupère, met en cache, déclenche `EmergencyManagerService`.
- Les **Controllers** lisent le cache (pas les clients directement) → pas de requête simulateur à chaque appel front.
- Les **Types** (`FireType`, `VehicleType`…) portent la sémantique métier utilisée pour filtrer et scorer.

---

## Structure du projet

```
baldespompiers/
├── pom.xml
└── src/main/java/cpe/baldespompiers/
    ├── api/controller/      ← endpoints REST pour le front
    ├── service/             ← logique métier (dispatch, scoring, events)
    ├── client/              ← appels HTTP vers le simulateur
    ├── config/              ← WebClient, CORS, Security, ThreadPool
    ├── model/dto/           ← FireDto, VehicleDto, FacilityDto…
    ├── model/type/          ← VehicleType, LiquidType, EmergencyType…
    ├── thread/              ← EventPollerThread, VehicleMovementThread
    └── tools/               ← GisTools (calculs géo, fuel check)
```

---

## Lots

| Lot | Quoi | Facteur |
|-----|------|---------|
| **Lot 1** | Affichage feux/véhicules sur carte Leaflet + polling | ×1 |
| **Lot 2** | CRUD casernes + véhicules, affectation manuelle | ×1 |
| **Lot 3.1** | Téléportation + affectation gloutonne (distance) | ×2 |
| **Lot 3.2** | Déplacement ligne droite + greedy assignment | ×2 |
| **Lot 3.3** | Routage OSRM + optimisation multi-critères | ×3 |
| **Lot 4** | Carburant, extincteur, équipage, fatigue | Variante |

---

## Ressources

- Simulateur swagger : `http://tp.cpe.fr:8083/swagger-ui/index.html`
- Lib externe (DTOs/Enums) : `https://gitlab.com/js-project-gis-1/js-fire-simulator-public`
- Leaflet docs : `https://leafletjs.com/`

---

# FAIT

### Dispatch & scoring

- **Scoring normalisé [0,1]** — `vehicleScore` calcule une somme pondérée de 5 composantes (toutes ∈ [0,1]) : `vehicleNorm` (efficacité × ratio crew / MAX_EFFICIENCY), `liquidScore` (efficacité liquide sur le type de feu), `distanceScore` (hyperbole `1/(1+d/dRef)`, `dRef≈2km`), `liquidRatio`, `fuelRatio` ; poids configurables dans `application.properties`
- **Stratégie 2 seuils** — `dispatchFires` tente d'abord les véhicules avec ressources confortables (Tier 1 `best_candidates` : fuel ≥ `readyFuel`, liquid ≥ `readyLiquid`, crew ≥ `readyCrew`) ; si aucun, fallback sur les seuils minimaux (Tier 2 `candidates`) pour ne pas laisser le feu s'étendre ; en dessous du minimum ou liquide incompatible : jamais dispatchés
- **Tri des feux** — ordre de priorité : (0) feux non couverts par une autre équipe, (1) feux avec peu de véhicules compatibles (ressource rare), (2) intensité décroissante, (3) à égalité, feu dont le meilleur `vehicleScore` disponible est le plus élevé (évite qu'un feu lointain vole le véhicule d'un feu proche)
- **Abandon feux faibles** — feux avec intensité ≤ `abandonIntensity + 2` ignorés (laissés aux autres équipes) ; marge +2 pour ne pas reboucler sur un feu quasi-éteint ; seuil à 0 pour les feux sur caserne
- **Vérification carburant aller-retour** — `GisTools.hasFuelToReach(vehicle, fireLon, fireLat, facilityLon, facilityLat)` vérifie que le véhicule peut rejoindre le feu ET revenir à la caserne avant de le dispatcher (`candidates()` dans `FireService` et `RPEventService`)
- **Détection extinction par autre équipe** — `lastKnownIntensity` (map id→intensité du tick précédent) dans `FireService` ; si intensité d'un feu non-assigné baisse → ajouté dans `beingExtinguishedByOthers` → déprioritisé dans le tri (critère 0) mais pas ignoré ; exception : candidat à moins de `dispatch.nearby-waypoints` pas du feu → non déprioritisé (déjà presque sur place)
- **Filtre compatibilité liquide** — `isLiquidCompatible(liquidType, fireType)` exclut tout véhicule dont le liquide a < 10% d'efficacité contre ce type de feu

### Déplacement

- **Vitesse max par type** — `computeStepDelay` calcule le délai entre chaque pas proportionnellement à `VehicleType.getMaxSpeed()` (référence 110 km/h) ; un CAR (150 km/h) avance plus vite, un PUMPER_TRUCK (70 km/h) plus lentement
- **Routage OSRM avec fallback** — mode `road` : requête OSRM, parse géométrie, déplacement segment par segment ; 3 cas de fallback en ligne droite (HTTP error, code invalide, pas de coordonnées) ; tronçon final hors-réseau routier toujours parcouru en ligne droite
- **Feu éteint en route** — `moveToPoint` vérifie périodiquement (toutes les `fireCheckDelayMs`) si le feu cible est quasi-éteint ; si oui, lève `FireGoneException` → `redirectAfterFireGone` libère le feu, cherche un autre via `findNextFireForVehicle` et repart directement ; si aucun disponible, retour caserne

### Gestion caserne & recharge

- **Support multi-casernes** — `knownFacilities` (lazy load) dans `FireService` ; `caserneOnFire()` détecte un feu sur n'importe laquelle de nos casernes ; `handleCasernefire()` rappelle le véhicule le plus compatible + le plus proche de LA caserne concernée
- **Protection caserne priorité absolue** — feux sur caserne traités avant tout autre feu ; Tier 0a : véhicule libre → dispatch immédiat ; Tier 0b : tous en mission → rappel forcé du plus compatible (cooldown 30s pour éviter les rappels répétés)
- **Recharge conditionnelle** — `vehicleNeedsRecharge` vérifie fuel < `minFuelForNewMission` ou liquid < `minLiquidForNewMission` ; `waitForRecharge(waitForFull=false)` attend les seuils `readyFuel`/`readyLiquid` (dispatch rapide), `waitForFull=true` attend 100% (avant repositionnement)
- **Retour à la caserne la plus proche** — après mission normale, `nearestFacility()` interroge toutes nos casernes et sélectionne la plus proche par distance euclidienne ; `returnToFacility` et `waitForRecharge` acceptent une `FacilityDto` cible optionnelle ; rappel forcé (feu sur caserne, bouton recall) → caserne d'origine conservée (`null`)
- **Rappel global** — `recall-all` rapatrie aussi les véhicules inactifs via `recallIdleVehicle()` ; s'annule proprement si le rappel est désactivé en cours de route (`ResumeMissionException`)

### Repositionnement & chaînage de missions

- **Repositionnement au centroïde** — quand un véhicule est inactif et chargé, `repositionIdleVehicles()` le détecte, le marque dans `repositioningVehicles` et lance `recallIdleVehicle()` (caserne → recharge 100% → centroïde des feux actifs) ; annulable à chaque pas si un dispatch arrive (`RepositioningCancelledException`)
- **Chaînage direct feu→feu et event→event** — après extinction/résolution, si ressources suffisantes, `findNextFireForVehicle` / `findNextEventForVehicle` est appelé ; si candidat trouvé, `claimFire` / `claimEvent` transfère l'assignation atomiquement et le véhicule repart sans passer par la caserne ; sinon `willReposition` décide du niveau de recharge

### Events (accidents / blessés)

- **Dispatch events** — `RPEventService.dispatchEvents()` score et affecte les véhicules aux `road_accident` et `personal_injury` ; `moveVehicleToEvent()` gère le déplacement, l'attente de résolution, la redirection si l'event est résolu en route (`redirectAfterEventGone`) et le retour caserne si ressources insuffisantes

### Robustesse & corrections

- **Bug concurrence `recallIdleVehicle`** — si un dispatch arrive pendant le retour/recharge/repositionnement, `returnToFacility` utilise la phase `TO_REPOSITION` (interruptible) ; `waitForRecharge` lève `RepositioningCancelledException` si le véhicule est dispatché ; `recallIdleVehicle` sort immédiatement via le `finally`
- **Position de départ stale** — au lancement du thread async, refetch `vehicleClient.getVehicleById` en tout début de `moveVehicle` et `moveVehicleToEvent` pour partir de la vraie position courante (pas la position potentiellement obsolète du poller)
- **Logs non répétitifs** — `waitForFireOut`, `waitForEventOut`, `waitForRecharge` ne loggent que lors d'un changement de valeur (variable locale `lastIntensity`/`lastFuel`/`lastLiquid`) ; feux non couverts loggés uniquement lors du changement d'état (`uncoveredFireIds`)
- **Pool de threads** — taille `vehicleMovementExecutor` ajustée dans `AppConfig` pour éviter les blocages lors des dispatches simultanés
- **Frontend** — rafraîchissement automatique toutes les 3s ; interface véhicules (`vehicles.html` + `vehicles.js`) refactorisée


