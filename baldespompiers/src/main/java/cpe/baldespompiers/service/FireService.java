package cpe.baldespompiers.service;

import cpe.baldespompiers.client.FacilityClient;
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.model.type.EmergencyType;
import cpe.baldespompiers.model.type.LiquidType;
import cpe.baldespompiers.model.type.VehicleType;
import cpe.baldespompiers.tools.GisTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FireService {

    private static final Logger log = LoggerFactory.getLogger(FireService.class);

    private final EmergencyManagerService emergencyManagerService;
    private final FacilityClient facilityClient;

    @Value("${simulator.team-uuid}")
    private String teamUuid;



    /** Rayon de détection d'un feu "sur la caserne" (~200 m en degrés). */
    @Value("${dispatch.caserne.fire-radius:0.002}")
    private double caserneFireRadius;

    /** Cooldown entre deux rappels pour le même feu de caserne (évite les rappels répétés). */
    @Value("${dispatch.caserne.recall-cooldown-ms:30000}")
    private long recallCooldownMs;

    /** Liste de toutes nos casernes, chargée en lazy au premier dispatch. */
    private final AtomicReference<List<FacilityDto>> knownFacilities = new AtomicReference<>(null);



    /** Timestamp du dernier rappel émis par fire ID, pour éviter les rappels répétés. Pour le feu à la caserne */
    private final Map<Integer, Long> recallIssuedAt = new ConcurrentHashMap<>();

    /** Intensité de CHAQUE FEU DE LA MAP au tick précédent, pour détecter si une autre équipe est en train de l'éteindre. */
    private final Map<Integer, Float> lastKnownIntensity = new ConcurrentHashMap<>();

    /** IDs des feux non couverts (traités par aucun véhicule) au dernier tick — pour logguer uniquement lors des changements d'état. */
    private final Set<Integer> uncoveredFireIds = ConcurrentHashMap.newKeySet();

    @Value("${dispatch.abandon.intensity:4}")
    private int abandonIntensity;

    // Seuil absolu : en dessous, le véhicule est exclu du dispatch
    @Value("${dispatch.min.fuel:10.0}")
    private float minFuel;

    @Value("${dispatch.min.liquid:10.0}")
    private float minLiquid;

    @Value("${dispatch.min.crew:1}")
    private int minCrew;

    // Seuil "prêt" : on préfère envoyer un véhicule au-dessus de ces valeurs.
    // Si aucun n'est disponible, on accepte tout véhicule > seuil minimum.
    @Value("${dispatch.ready.fuel:40.0}")
    private float readyFuel;

    @Value("${dispatch.ready.liquid:40.0}")
    private float readyLiquid;

    @Value("${dispatch.ready.crew:3}")
    private int readyCrew;




    // Poids normalisés du vehicleScore — somme = 1.0
    @Value("${dispatch.w-vehicle:0.40}")
    private double wVehicle;

    @Value("${dispatch.w-liquid:0.30}")
    private double wLiquid;

    @Value("${dispatch.w-distance:0.20}")
    private double wDistance;

    @Value("${dispatch.w-liquid-ratio:0.07}")
    private double wLiquidRatio;

    @Value("${dispatch.w-fuel:0.03}")
    private double wFuel;

    // Seuil de distance pour la courbe hyperbole 1/(1+d/dRef), en degrés (≈ 0.018° ≈ 2 km à Lyon)
    // et 2km est un bon compromis pour privilégier les véhicules proches sans exclure les véhicules plus éloignés qui pourraient être les seuls compatibles pour ce feu
    @Value("${dispatch.d-ref:0.018}")
    private double dRef;

    // Taille d'un pas de déplacement en degrés — sert à convertir une distance en nombre de waypoints
    // Un véhicule à moins de dispatch.nearby-waypoints waypoints du feu n'est pas déprioritisé même si une autre équipe l'éteint déjà
    @Value("${movement.step.size:0.0005}")
    private double stepSize;

    @Value("${dispatch.nearby-waypoints:100}")
    private int nearbyWaypoints;

    // Efficacité max possible dans l'enum VehicleType (TRUCK.FIRE = 50f)
    private static final double MAX_VEHICLE_EFFICIENCY = 50.0;





    public FireService(@Lazy EmergencyManagerService emergencyManagerService,
                       FacilityClient facilityClient) {
        this.emergencyManagerService = emergencyManagerService;
        this.facilityClient = facilityClient;
    }

    // ── Compatibilité liquide ──────────────────────────────────────────────────

    /** Vrai si le liquide du véhicule est efficace contre ce type de feu (efficacité > 10 %). */
    private boolean isLiquidCompatible(LiquidType liquid, String fireType) {
        if (liquid == null || fireType == null) return false; // données manquantes → incompatible par sécurité
        // Un seuil de 10 % évite d'envoyer un véhicule totalement inefficace (ex. eau sur feu chimique)
        return liquid.getEfficiency(fireType) > 0.1;
    }

    // ── Calcul Distance au feu ──────────────────────────────────────────────────
    private static double calcule_distance(double lat1, double lon1, double lat2, double lon2) {
        double dLon = lon1 - lon2;
        double dLat = lat1 - lat2;
        return Math.sqrt(dLon * dLon + dLat * dLat); // en degrés — cohérent avec dRef (aussi en degrés)
    }

    // ── Score d'aptitude ───────────────────────────────────────────────────────

    /**
     * Score normalisé [0, 1] d'un véhicule pour un feu donné.
     * Formule : somme pondérée de 5 composantes, chacune ∈ [0, 1], poids = 1.0.
     *
     * vehicleNorm  = (efficiency[FIRE]/ MAX_VEHICLE_EFFICIENCY) × crewRatio -->  (formule du sujet)
     * liquidScore  = liquidType.efficiency(fireType)
     * distanceScore = 1 / (1 + dist / dRef)   (hyperbole — jamais négatif car on veut privilégier les véhicules proches, mais on ne veut pas exclure les véhicules plus éloignés qui pourraient être les seuls compatibles pour ce feu)
     * liquidRatio  = currentLiquid / liquidCapacity
     * fuelRatio    = currentFuel   / fuelCapacity
     */
    private double vehicleScore(VehicleDto v, FireDto fire) {
        // vehicleNorm : formule d'atténuation du sujet × ratio crew, normalisée sur [0, 1]
        double crewRatio   = (v.getType() != null && v.getType().getVehicleCrewCapacity() > 0)
                           ? (double) v.getCrewMember() / v.getType().getVehicleCrewCapacity()
                           : 0.0;
        double vehicleEff  = (v.getType() != null)
                           ? v.getType().getEfficiencyMap().getOrDefault(EmergencyType.FIRE, 0f)
                           : 0.0;
        double vehicleNorm = (vehicleEff / MAX_VEHICLE_EFFICIENCY) * crewRatio; // normalisé sur [0, 1] grâce à la division par MAX_VEHICLE_EFFICIENCY (efficacité max possible dans l'enum VehicleType)

        // liquidScore : efficacité du liquide sur le type de feu, ∈ [0, 1]
        double liquidScore = (v.getLiquidType() != null)
                           ? v.getLiquidType().getEfficiency(fire.getType())
                           : 0.0;

        // distanceScore : courbe hyperbole, ∈ (0, 1] — jamais négatif
        double dist          = calcule_distance(v.getLat(), v.getLon(), fire.getLat(), fire.getLon());
        double distanceScore = 1.0 / (1.0 + dist / dRef);

        // liquidRatio : réservoir de liquide restant, ∈ [0, 1]
        double liquidRatio = (v.getLiquidType() != null && v.getType().getLiquidCapacity() > 0)
                           ? v.getLiquidQuantity() / v.getType().getLiquidCapacity()
                           : 0.0;

        // fuelRatio : réservoir de carburant restant, ∈ [0, 1]
        double fuelRatio   = (v.getType() != null && v.getType().getFuelCapacity() > 0)
                           ? v.getFuelQuantity() / v.getType().getFuelCapacity()
                           : 0.0;

        return vehicleNorm   * wVehicle     // *0,40
             + liquidScore   * wLiquid      // *0,30
             + distanceScore * wDistance    // *0,20
             + liquidRatio   * wLiquidRatio // *0,07
             + fuelRatio     * wFuel;       // *0,03
    }





    // ── Filtres de candidats ───────────────────────────────────────────────────

    private Optional<FacilityDto> facilityOf(VehicleDto v) { // pour les véhicules qui ont une référence de caserne, récupère les coordonnées de cette caserne (pour calculer la distance totale aller-retour dans le filtre candidates)
        List<FacilityDto> facilities = knownFacilities.get();
        if (facilities == null || v.getFacilityRefID() == null) return Optional.empty();
        return facilities.stream().filter(f -> f.getId().equals(v.getFacilityRefID())).findFirst(); //on recup l'id de la caserne DU véhicule en paramètre
    }

    /** Véhicules libres, au-dessus des seuils minimaux, compatibles avec le type de feu et avec assez de carburant pour aller au feu ET rentrer à la caserne. */
    private Stream<VehicleDto> candidates(List<VehicleDto> vehicles, FireDto fire) {
        return vehicles.stream()
                .filter(v -> !emergencyManagerService.getVehicleStates().containsKey(v.getId())) // véhicule libre (pas déjà en mission)
                .filter(v -> isLiquidCompatible(v.getLiquidType(), fire.getType())) // au moins 10% d'efficacité du liquide contre ce type de feu
                .filter(v -> v.getCrewMember() >= minCrew)
                .filter(v -> {                                          // ← filtre CAR
                    if (v.getType() == VehicleType.CAR) return v.getLiquidQuantity() >= 8f; // les voitures ont une petite capacité de liquide, on accepte un seuil plus bas pour ne pas les exclure systématiquement (elles sont souvent compatibles mais avec peu de liquide, ce qui est mieux que rien pour les feux faibles ou les feux avec blessés)
                    return v.getLiquidQuantity() >= minLiquid; // si pas CAR, on applique le seuil normal
                })
                .filter(v -> v.getFuelQuantity() >= minFuel)
                .filter(v -> facilityOf(v) // si on connaît la caserne de ce véhicule, vérifie qu'il a assez de carburant pour aller au feu ET revenir à la caserne ; sinon, vérifie juste qu'il a assez de carburant pour aller au feu (par sécurité, on ne veut pas exclure un véhicule juste parce qu'on n'a pas les infos de sa caserne, surtout s'il est proche du feu)
                        .map(f -> GisTools.hasFuelToReach(v, fire.getLon(), fire.getLat(), f.getLon(), f.getLat()))
                        .orElseGet(() -> GisTools.hasFuelToReach(v, fire.getLon(), fire.getLat())));
    }

    /** Véhicules "prêts" : candidats valides avec ressources au-dessus des seuils préférés. */
    private Stream<VehicleDto> best_candidates(List<VehicleDto> vehicles, FireDto fire) {
        return candidates(vehicles, fire) // on se base sur les candidats déjà filtrés pour ne pas répéter les mêmes filtres (compatibilité, crew, fuel min, liquid min), puis on applique les seuils "ready" pour ne garder que les véhicules confortables à envoyer en priorité
                .filter(v -> !emergencyManagerService.getVehicleStates().containsKey(v.getId())) // on rechek ca au cas où le véhicule serait passé en mission entre temps (mieux vaut exclure un véhicule qui vient d'être pris que de risquer de le dispatcher alors qu'il est déjà en mission)
                .filter(v -> {
                    if (v.getType() == VehicleType.CAR) return v.getLiquidQuantity() >= 8f;
                    return v.getLiquidQuantity() >= readyLiquid;
                })
                .filter(v -> !emergencyManagerService.getVehicleStates().containsKey(v.getId()))
                .filter(v -> isLiquidCompatible(v.getLiquidType(), fire.getType()))
                .filter(v -> v.getFuelQuantity() >= readyFuel)
                .filter(v -> v.getCrewMember() >= readyCrew);
    }

    // ── Protection caserne ─────────────────────────────────────────────────────

    /** Charge toutes nos casernes dans une variable une seule fois via notre teamUuid. */
    private void ensureFacilityList() {
        if (knownFacilities.get() != null) return; //coords de nos casernes déjà chargées
        List<FacilityDto> facilities = facilityClient.getAllFacilities(teamUuid);
        if (facilities == null || facilities.isEmpty()) return;
        knownFacilities.set(facilities);
        log.info("{} caserne(s) chargée(s)", facilities.size());
        facilities.forEach(f -> log.info("  → caserne #{} '{}' lon={} lat={}", f.getId(), f.getName(), f.getLon(), f.getLat()));
    }

    /** Vrai si ce feu menace une de nos casernes. */
    // C'est la méthode qui sera appelée pour chaque feu dans dispatchFires, avant même de faire le tri des feux, pour garantir une priorité absolue aux feux sur caserne (traités avant tout autre feu, même les feux avec blessés).
    public boolean isCaserneFire(FireDto fire) {
        ensureFacilityList();
        return caserneOnFire(fire) != null;
    }
    
    /** Retourne la caserne menacée par ce feu, ou null si aucune n'est concernée. */
    private FacilityDto caserneOnFire(FireDto fire) {
        List<FacilityDto> facilities = knownFacilities.get();
        if (facilities == null) return null;
        return facilities.stream()
                .filter(f -> calcule_distance(fire.getLat(), fire.getLon(), f.getLat(), f.getLon()) < caserneFireRadius) // si le feu est à moins de caserneFireRadius degrés de la caserne, on considère que la caserne est menacée (environ 200 m, à ajuster selon les tests)
                .findFirst()
                .orElse(null);
    }

    /**
     * Gère un feu détecté sur une caserne avec priorité absolue.
     * Essaie d'abord un véhicule libre ; si tous sont en mission, rappelle le meilleur
     * (liquide compatible + le plus proche de la caserne menacée).
     */
    // appelé dans dispatchFires après verif caserneOnFire
    private void handleCasernefire(FireDto fire, FacilityDto caserne, List<VehicleDto> vehicles) {
        // Tier 0a : véhicule libre compatible → dispatch immédiat
        Optional<VehicleDto> free = candidates(vehicles, fire) // ne prend que les dispos
                .max(Comparator.comparingDouble(v -> vehicleScore(v, fire)));
        if (free.isPresent()) {
            log.error("=== FEU CASERNE '{}' #{} — dispatch immédiat du véhicule libre {} ===", caserne.getName(), fire.getId(), free.get().getId());
            emergencyManagerService.dispatch(free.get(), fire);
            return; // on examine pas les autres tiers si on a trouvé un véhicule libre, même si il y en a plusieurs (pas besoin de rappeler un autre véhicule si on en a déjà un qui part)
        }

        // Cooldown : ne pas rappeler plusieurs fois pour le même feu.
        // Attention car si une ambulance doit y aller AUSSI, ca peut poser problème ???
        Long last = recallIssuedAt.get(fire.getId());
        if (last != null && System.currentTimeMillis() - last < recallCooldownMs) {
            log.debug("Feu #{} caserne {} — rappel déjà en cours, attente de 30s", fire.getId(), caserne.getId());
            return;
        }

        // Tier 0b : tous en mission → rappeler le plus compatible (efficacité max), puis le plus proche de CETTE caserne à égalité
        vehicles.stream()
                .filter(v -> emergencyManagerService.getVehicleStates().containsKey(v.getId())) // on prend tout ceux en mission
                .filter(v -> v.getType() != null && v.getType().getLiquidCapacity() > 0) // exclure ambulances pour le moment
                .filter(v -> isLiquidCompatible(v.getLiquidType(), fire.getType())) // on ne garde que les compatibles
                .max(Comparator.comparingDouble((VehicleDto v) -> // on garde le plus compatible au liquide anti-feu ET on inclue la distance à la caserne
                        v.getLiquidType().getEfficiency(fire.getType()) * wLiquid
                        + (1.0 / (1.0 + calcule_distance(v.getLat(), v.getLon(), caserne.getLat(), caserne.getLon()) / dRef)) * wDistance))
                .ifPresent(v -> {
                    log.error("=== FEU CASERNE '{}' #{} — rappel forcé du véhicule {} ===", caserne.getName(), fire.getId(), v.getId());
                    // a la place de requestRecall, il faudrait pas plutot faire dispatch(v, fire) ???
                    emergencyManagerService.requestRecall(v.getId()); // ajoute ce véhicule à la liste des véhicules à rappeler (rappel unitaire) ; le mouvement de rappel sera géré dans moveVehicle, qui vérifiera régulièrement si un rappel est demandé pour ce véhicule et le redirigera vers la caserne au lieu du feu
                    recallIssuedAt.put(fire.getId(), System.currentTimeMillis()); // enregistre le timestamp de ce rappel pour éviter les rappels répétés tant que ce feu n'est pas traité
                });
    }







    // ── Dispatch feux ──────────────────────────────────────────────────────────

    public void dispatchFires(List<FireDto> fires, List<VehicleDto> vehicles) {
        ensureFacilityList(); // charge les casernes si pas déjà fait, pour pouvoir identifier les feux sur caserne et leur donner la priorité absolue dans le dispatch

        // Priorité absolue : feux sur une caserne traités avant tout le reste
        fires.stream()
                .filter(f -> !emergencyManagerService.getAssignedFires().contains(f.getId())) // ignore les feux déjà pris en charge par un véhicule (attention, compliqué d'envoyer un camion de pompier ET une ambulance ducoup ???)
                .forEach(f -> {
                    FacilityDto caserne = caserneOnFire(f);
                    if (caserne != null) handleCasernefire(f, caserne, vehicles);
                });

        // Détecte les feux dont l'intensité baisse depuis le dernier tick : une autre équipe s'en occupe déjà, le temps qu'on arrive ...
        double nearbyThresholdDeg = stepSize * nearbyWaypoints; // seuil en degrés en dessous duquel un véhicule est considéré "proche" du feu
        Set<Integer> beingExtinguishedByOtherTeam = fires.stream()
                .filter(f -> !emergencyManagerService.getAssignedFires().contains(f.getId())) // pas encore assigné à notre equipe
                .filter(f -> {
                    Float prev = lastKnownIntensity.get(f.getId());
                    return prev != null && f.getIntensity() < prev; // intensité en baisse → quelqu'un d'autre l'éteint
                })
                // Ne déprioritise pas si un véhicule candidat est déjà à moins de nearbyWaypoints pas du feu
                // (il serait dommage de laisser passer un feu presque arrivé juste parce qu'une autre équipe commence à peine à l'éteindre)
                .filter(f -> candidates(vehicles, f)
                        .mapToDouble(v -> calcule_distance(v.getLat(), v.getLon(), f.getLat(), f.getLon()))
                        .min()
                        .orElse(Double.MAX_VALUE) >= nearbyThresholdDeg)
                .map(FireDto::getId) // on garde juste les IDs pour le tri, pas besoin de garder les objets complets
                .collect(Collectors.toSet()); // les feux en cours d'extinction par d'autres équipes seront traités en dernier, après tous les autres feux (même les feux faibles ou sans blessés), pour ne pas gaspiller nos ressources sur des feux que d'autres équipes sont déjà en train de gérer efficacement

        if (!beingExtinguishedByOtherTeam.isEmpty())
            log.debug("Feux en cours d'extinction par d'autres équipes (pas en priorité) : {}", beingExtinguishedByOtherTeam);

        // Trie les feux pour traiter en priorité ceux qu'il est le plus difficile de couvrir
        // (évite qu'un feu "rare" voie son seul véhicule compatible partir sur un autre feu d'abord)
        List<FireDto> sortedFires = fires.stream()
                .filter(f -> f.getIntensity() > abandonIntensity + 2) // laisse les feux quasi-éteints aux autres équipes avec une petite marge de +2 pour ne pas y retourner en boucle
                .sorted(Comparator
                // 0. Feux non pris en charge par d'autres équipes en premier (intensité stable ou croissante)
                .comparingInt((FireDto f) -> beingExtinguishedByOtherTeam.contains(f.getId()) ? 1 : 0) //compairingInt trie du plus petit au plus grand, donc les feux avec score 0 (pas en extinction par d'autres équipes) seront traités avant les feux avec score 1 (en extinction par d'autres équipes)
                // 1. Feux avec peu de véhicules compatibles en premier (véhicules "rares" réservés)
                .thenComparingInt((FireDto f) -> (int) candidates(vehicles, f).count())
                // 2. À égalité de compatibilité, les plus intenses d'abord
                .thenComparingDouble(f -> -f.getIntensity()) // -f pour un tri décroissant car comparingDouble trie du plus petit au plus grand, on met un signe moins pour inverser l'ordre et traiter les feux les plus intenses en premier à égalité de compatibilité
                // 3. À égalité d'intensité, favorise le feu avec le meilleur vehicleScore max disponible
                //    (= le feu le plus proche/compatible du meilleur véhicule libre) — évite qu'un feu
                //    lointain mais intense vole le véhicule d'un feu proche équivalent
                .thenComparingDouble((FireDto f) -> -candidates(vehicles, f) // -candidates pour un tri décroissant des scores !!!!
                        .mapToDouble(v -> vehicleScore(v, f))
                        .max()
                        .orElse(0.0)))
                // 4. Feux sans blessés en premier (libère les véhicules plus vite pour les feux complexes)
                // .thenComparingInt((FireDto f) -> (f.getInjuredPeopleDtoList() == null || f.getInjuredPeopleDtoList().isEmpty()) ? 0 : 1)
                .toList(); // on collecte dans une liste triée pour pouvoir la réutiliser plusieurs fois dans la boucle de dispatch, sans refaire le tri à chaque fois

        Set<Integer> nowUncovered = new HashSet<>(); // feux sans véhicule disponible ce tick

        for (FireDto fire : sortedFires) {
            if (emergencyManagerService.getAssignedFires().contains(fire.getId())) continue; // un véhicule est déjà en route → on passe

            // Tier 1 : cherche un véhicule avec ressources confortables (fuel ≥ readyFuel, liq ≥ readyLiquid)
            Optional<VehicleDto> ready = best_candidates(vehicles, fire)
                    .max(Comparator.comparingDouble(v -> vehicleScore(v, fire))); // prend le meilleur score

            if (ready.isPresent()) {
                emergencyManagerService.dispatch(ready.get(), fire); // envoie le meilleur véhicule prêt
                continue; // inutile d'examiner le Tier 2 pour ce feu
            }

            // Tier 2 : aucun véhicule "prêt" → envoie le moins mauvais disponible au-dessus des seuils minimaux
            Optional<VehicleDto> fallback = candidates(vehicles, fire)
                    .max(Comparator.comparingDouble(v -> vehicleScore(v, fire)));

            if (fallback.isPresent()) {
                VehicleDto vehicle = fallback.get();
                log.warn("Feu #{} — aucun véhicule prêt complètement (fuel≥{}/liq≥{}), dispatch avec ressources partielles : véhicule {} (fuel={}, liq={})",
                        fire.getId(), readyFuel, readyLiquid,
                        vehicle.getId(), vehicle.getFuelQuantity(), vehicle.getLiquidQuantity());
                emergencyManagerService.dispatch(vehicle, fire);


            // ----------------------------------------------------------
            } else { // JUSTE DES LOGS
                // Feu non couvert — diagnostic loggué uniquement si l'état change (pas de spam à chaque tick)
                nowUncovered.add(fire.getId()); // ce feu est non couvert ce tick
                if (!uncoveredFireIds.contains(fire.getId())) {  // ce feu était couvert au tick précédent, il vient de devenir non couvert → on loggue un avertissement de diagnostic
                    boolean allBusy = vehicles.stream()
                            .allMatch(v -> emergencyManagerService.getVehicleStates().containsKey(v.getId())); // tous les véhicules sont en mission (aucun n'est disponible pour ce feu)
                    boolean incompatible = !allBusy && vehicles.stream()
                            .filter(v -> !emergencyManagerService.getVehicleStates().containsKey(v.getId()))
                            .noneMatch(v -> isLiquidCompatible(v.getLiquidType(), fire.getType())); // aucun véhicule libre n'est compatible avec ce type de feu
                    if (allBusy)
                        log.warn("Feu #{} (type={}) — non couvert : tous les véhicules sont en mission", fire.getId(), fire.getType());
                    else if (incompatible)
                        log.warn("Feu #{} (type={}) — non couvert : aucun véhicule avec liquide compatible", fire.getId(), fire.getType());
                    else
                        log.warn("Feu #{} (type={}) — non couvert : tous les véhicules compatibles déjà en mission OU sous le seuil (fuel<{}/liq<{})", fire.getId(), fire.getType(), readyFuel, readyLiquid);
                }
            }
        }

        // Log les feux qui viennent d'être couverts (étaient non couverts au tick précédent)
        uncoveredFireIds.stream()
                .filter(id -> !nowUncovered.contains(id)) // ce feu était non couvert au tick précédent, il vient d'être couvert → on loggue une info de diagnostic
                .forEach(id -> log.info("Feu #{} — maintenant couvert ou éteint", id));
        uncoveredFireIds.retainAll(nowUncovered); // met à jour la liste des feux non couverts pour le prochain tick : on garde uniquement les feux qui sont encore non couverts ce tick (on retire les feux qui viennent d'être couverts)
        uncoveredFireIds.addAll(nowUncovered); //  puis on ajoute les nouveaux feux non couverts de ce tick



        // -----------------------------------------------------------------------------

        // Snapshot des intensités pour la détection d'extinction par d'autres équipes
        fires.forEach(f -> lastKnownIntensity.put(f.getId(), f.getIntensity()));
        // Nettoie les feux éteints/disparus des deux maps
        Set<Integer> activeIds = fires.stream().map(FireDto::getId).collect(Collectors.toSet());
        lastKnownIntensity.keySet().removeIf(id -> !activeIds.contains(id));
        uncoveredFireIds.removeIf(id -> !activeIds.contains(id));

    }







    /**
     * Cherche le meilleur feu non-assigné compatible avec ce véhicule pour un départ direct
     * (sans repassage par la caserne). Réutilise isLiquidCompatible et vehicleScore.
     */
    // appelé par vehicleMovementThread pendant le déplacement d'un véhicule déjà en mission, pour lui trouver un nouveau feu à traiter directement depuis son feu actuel, sans repasser par la caserne (gain de temps, surtout si le véhicule est déjà proche d'un autre feu compatible)
    public Optional<FireDto> findNextFireForVehicle(VehicleDto vehicle, List<FireDto> activeFires) {
        return activeFires.stream()
                .filter(f -> f.getIntensity() > abandonIntensity + 2)                    // ignore les feux éteints ou quasi-éteints (laissés aux autres équipes), avec une petite marge pour ne pas boucler sur le feux qu'on vient de laisser
                .filter(f -> !emergencyManagerService.getAssignedFires().contains(f.getId())) // ignore les feux déjà pris
                .filter(f -> isLiquidCompatible(vehicle.getLiquidType(), f.getType())) // liquide efficace requis
                .max(Comparator.comparingDouble(f -> vehicleScore(vehicle, f)));      // prend le feu pour lequel ce véhicule est le plus efficace
    }

    /**
     * Transition directe d'un feu à un autre (sans caserne) :
     * libère l'ancien feu dans assignedFires, prend le nouveau, repasse en MOVING.
     */
    // appelé par vehicleMovementThread lorsqu'un véhicule arrive sur un feu, pour lui faire faire une transition directe vers un autre feu compatible trouvé par findNextFireForVehicle, sans repasser par la caserne (gain de temps, surtout si le nouveau feu est proche du feu actuel)
    public void claimFire(Integer vehicleId, Integer oldFireId, Integer newFireId) {
        emergencyManagerService.getAssignedFires().remove(oldFireId); // libère l'ancien feu immédiatement (un autre véhicule pourra le récupérer)
        emergencyManagerService.getAssignedFires().add(newFireId);    // réserve le nouveau avant même de partir (évite un double dispatch)
        emergencyManagerService.getVehicleStates().put(vehicleId, EmergencyManagerService.VehicleState.MOVING); // repasse en déplacement (il n'est plus ON_FIRE)
        log.info("Véhicule {} : transition feu #{} → feu #{} (sans caserne)", vehicleId, oldFireId, newFireId);
    }

    /** Libère uniquement l'assignation du feu (appel du finally dans moveVehicle), sans changer l'état du véhicule. */
    public void releaseFire(Integer fireId) {
        emergencyManagerService.getAssignedFires().remove(fireId);
        recallIssuedAt.remove(fireId);
    }
}
