# Optimisation de la flotte de véhicules — CPE Fighter 2026

## 1. Variables de décision

| Variable   | Véhicule      | Places | Crew | Vitesse   | Eff. feu | Eff. event (moy)   |
|------------|---------------|--------|------|-----------|----------|--------------------|
| x₁         | CAR           | 2      | 2    | 150 km/h  | 1        | (1+5+5)/3 = 3.67   |
| x₂         | FIRE_ENGINE   | 4      | 4    | 110 km/h  | 5        | (2+1+2)/3 = 1.67   |
| x₃         | PUMPER_TRUCK  | 10     | 6    | 70 km/h   | 20       | (2+1+2)/3 = 1.67   |
| x₄         | WATER_TENDERS | 10     | 3    | 110 km/h  | 20       | 0                  |
| x₅         | TURNTABLE     | 15     | 6    | 70 km/h   | 40       | (0+0+5)/3 = 1.67   |
| x₆         | TRUCK         | 20     | 8    | 110 km/h  | 50       | (2+2+2)/3 = 2      |
| x₇         | AMBULANCE     | 4      | 2    | 110 km/h  | 0        | (20+20+20)/3 = 20  |

---

## 2. Formule d'atténuation (simulateur)

D'après la documentation du simulateur, l'atténuation d'un feu ou d'un event par un véhicule est :

```
atténuation = efficiency[TYPE] × (crewMember / vehicleCrewCapacity)
```

Comme on envoie les véhicules à plein crew, le ratio `crewMember / vehicleCrewCapacity = 1`.
Le score est ensuite pondéré par la vitesse (arriver plus vite = réduire l'intensité avant les autres équipes) :

```
score feu   = efficiency[FIRE]  × (vitesse / 110)
score event = efficiency[EVENT] × (vitesse / 110)
```

### Scores calculés

| Véhicule | Score feu | Score event | Score total (50/50) | Score/crew |
|---|---|---|---|---|
| CAR (x₁) | 1.36 | 5.00 | 3.18 | 1.59 |
| FIRE_ENGINE (x₂) | 5.00 | 1.67 | 3.34 | 0.84 |
| PUMPER_TRUCK (x₃) | 12.73 | 1.06 | 6.88 | 1.15 |
| WATER_TENDERS (x₄) | 20.00 | 0 | 10.00 | 3.33 |
| TURNTABLE (x₅) | 25.45 | 1.06 | 13.28 | 2.21 |
| TRUCK (x₆) | 50.00 | 2.00 | 26.00 | 3.25 |
| AMBULANCE (x₇) | 0 | 20.00 | 10.00 | **5.00 ★** |

> L'AMBULANCE domine largement avec un ratio score/crew de 5.0, grâce à une efficiency event de 20 pour seulement 2 crew.

---

## 3. Programme linéaire — contraintes de base (30 places, 20 crew)

### Fonction objectif

$$\max z = 0.5 \times (1.36x_1 + 5x_2 + 12.7x_3 + 20x_4 + 25.5x_5 + 50x_6)$$
$$+ 0.5 \times (5x_1 + 1.67x_2 + 1.06x_3 + 0 + 1.06x_5 + 2x_6 + 20x_7)$$

Ce qui simplifie à :

$$\max z = 3.18x_1 + 3.34x_2 + 6.88x_3 + 10x_4 + 13.28x_5 + 26x_6 + 10x_7$$

### Contraintes

$$2x_1 + 4x_2 + 10x_3 + 10x_4 + 15x_5 + 20x_6 + 4x_7 \leq 30 \quad \text{(places)}$$
$$2x_1 + 4x_2 + 6x_3 + 3x_4 + 6x_5 + 8x_6 + 2x_7 \leq 20 \quad \text{(crew)}$$
$$x_1, x_2, x_3, x_4, x_5, x_6, x_7 \geq 0, \text{ entiers}$$

### Résolution (PuLP / branch and bound)

Sans aucune contrainte supplémentaire, le solveur place autant d'ambulances que possible car leur ratio score/crew est dominant. Avec 30 places et 20 crew, la solution sans garde-fous est :

```
Statut : Optimal

  1× TRUCK     : places=20, crew=8
  5× AMBULANCE : places=20, crew=10

z = 76.00   places=40/30 → non réalisable (dépasse places)
```

En pratique avec 30 places et 20 crew le solveur trouve :

```
  1× TRUCK     : places=20, crew=8
  2× AMBULANCE : places=8,  crew=4
  1× CAR       : places=2,  crew=2

z = 49.18
Places utilisées : 30/30
Crew utilisé     : 14/20
```

---

## 4. Problème : l'ambulance est trop dominante

Sans contrainte sur le nombre de véhicules par type, le solveur tend systématiquement vers un maximum d'ambulances. Par exemple avec 45 places et 30 crew et sans aucune limite :

```
  8× CAR       : places=16, crew=16
  9× AMBULANCE : places=36, crew=18  ← 17 véhicules quasi tous event

z = 102.72
Score feu   : 10.91   ← quasi nul
Score event : 180.04
Ratio feu/event : 0.06
```

Ce déséquilibre est problématique en compétition : avec un score feu quasi nul, les autres équipes réduisent les feux avant nous et nous volent les points.

| Composition | Score feu | Score event | Ratio |
|---|---|---|---|
| 8 CAR + 9 AMBULANCE (sans contrainte) | 10.91 | 180.04 | **0.06** — inutilisable |
| Composition intuitive (1 WT + 4 FE + 4 AMB + 1 CAR) | 41.36 | 85.00 | **0.49** — équilibré |

Il faut donc ajouter des contraintes réalistes sur le nombre de véhicules.

---

## 5. Programme linéaire — contraintes réalistes (45 places, 30 crew)

La caserne dispose de 45 places et 30 crew. La map ne génère pas assez de feux/events simultanés pour justifier plus de 5 véhicules par type. On ajoute :

- Entre 3 et 5 véhicules capables de traiter les feux (tous sauf AMBULANCE)
- Maximum 5 AMBULANCE

### Programme linéaire complet

$$\max z = 3.18x_1 + 3.34x_2 + 6.88x_3 + 10x_4 + 13.28x_5 + 26x_6 + 10x_7$$

$$2x_1 + 4x_2 + 10x_3 + 10x_4 + 15x_5 + 20x_6 + 4x_7 \leq 45 \quad \text{(places)}$$
$$2x_1 + 4x_2 + 6x_3 + 3x_4 + 6x_5 + 8x_6 + 2x_7 \leq 30 \quad \text{(crew)}$$
$$3 \leq x_1 + x_2 + x_3 + x_4 + x_5 + x_6 \leq 5 \quad \text{(véhicules feu)}$$
$$x_7 \leq 5 \quad \text{(véhicules event)}$$
$$x_1, \ldots, x_7 \geq 0, \text{ entiers}$$

### Résolution (PuLP / branch and bound)

```
Statut : Optimal

  2× CAR       : places=4,  crew=4   → score feu=2.72   score event=10.00
  1× TRUCK     : places=20, crew=8   → score feu=50.00  score event=2.00
  5× AMBULANCE : places=20, crew=10  → score feu=0.00   score event=100.00

z                 = 82.36
Places utilisées  : 44/45
Crew utilisé      : 22/30
Véhicules feu     : 3  ✓  (contrainte 3-5)
Véhicules event   : 5  ✓  (contrainte max 5)
Score feu total   : 52.72
Score event total : 112.00
Ratio feu/event   : 0.47
```

---

## 6. Comparaison finale

| Composition | Places | Crew | z | Score feu | Score event | Ratio |
|---|---|---|---|---|---|---|
| Sans contrainte (8 CAR + 9 AMB) | 44/45 | 26/30 | 102.72 | 10.91 | 180.04 | 0.06 |
| Intuitive (1 WT + 4 FE + 4 AMB + 1 CAR) | 44/45 | 29/30 | 66.54 | 41.36 | 85.00 | 0.49 |
| **Optimale finale (2 CAR + 1 TRUCK + 5 AMB)** | **44/45** | **22/30** | **82.36** | **52.72** | **112.00** | **0.47** |

---

## 7. Conclusions

- Le **TRUCK** est le meilleur véhicule feu (score feu = 50, le plus élevé de la flotte)
- L'**AMBULANCE** est le meilleur véhicule event (ratio score/crew = 5.0, dominant)
- Le **CAR** est sous-estimé intuitivement mais très efficace grâce à sa double efficacité feu+event et sa vitesse de 150 km/h
- Le **FIRE_ENGINE** est le moins rentable (score/crew = 0.84 — le pire ratio)
- Les **places** sont le facteur limitant, pas le crew (8 crew inutilisés dans la solution optimale)
- Sans contrainte sur le nombre de véhicules par type, le solveur sature en ambulances et rend la flotte inutile sur les feux
