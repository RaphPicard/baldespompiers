const map = L.map('map', { zoomControl: true }).setView([45.75, 4.85], 13);

// Pane dédié véhicules — z-index CSS supérieur au pane markers par défaut (600)
// garantit que les véhicules passent TOUJOURS devant feux/events quelle que soit leur latitude
map.createPane('vehiclePane');
map.getPane('vehiclePane').style.zIndex = 620;
map.getPane('vehiclePane').style.pointerEvents = 'auto';

// Tile layer — Carto Voyager (plus moderne / coloré que light_all)
L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
  attribution: '© OpenStreetMap · © CARTO',
  subdomains: 'abcd',
  maxZoom: 19,
}).addTo(map);

// ── Refresh rates ───────────────────────────────────────────
const VEHICLE_REFRESH_MS = 250;   // poll serveur (4 Hz) — la fluidité vient de l'interpolation
const STATIC_REFRESH_MS  = 2000;
const TRAIL_SAMPLE_MS    = 80;    // fréquence d'échantillonnage des trails (depuis position interpolée)

// ── Trails ──────────────────────────────────────────────────
const TRAIL_MAX_POINTS = 200;    // ~16s à 80ms
const TRAIL_MIN_DIST   = 0.00003;

// Doit rester aligné avec VehicleType.java (cf. vehicles.js)
const VEHICLE_SPACE_BY_TYPE = {
  CAR: 2, FIRE_ENGINE: 4, PUMPER_TRUCK: 10, WATER_TENDERS: 10,
  TURNTABLE_LADDER_TRUCK: 15, TRUCK: 20, EMERGENCY_AMBULANCE: 4,
};
// efficiencyMap de VehicleType.java : 0 = pas compatible
const EFFICIENCY_BY_TYPE = {
  CAR:                   { FIRE: 1,  ROAD_ACCIDENT: 1, PERSONAL_INJURY: 5, MISCELLANEOUS_OPERATION: 5 },
  FIRE_ENGINE:           { FIRE: 5,  ROAD_ACCIDENT: 2, PERSONAL_INJURY: 1, MISCELLANEOUS_OPERATION: 2 },
  PUMPER_TRUCK:          { FIRE: 20, ROAD_ACCIDENT: 2, PERSONAL_INJURY: 1, MISCELLANEOUS_OPERATION: 2 },
  WATER_TENDERS:         { FIRE: 20, ROAD_ACCIDENT: 0, PERSONAL_INJURY: 0, MISCELLANEOUS_OPERATION: 0 },
  TURNTABLE_LADDER_TRUCK:{ FIRE: 40, ROAD_ACCIDENT: 0, PERSONAL_INJURY: 0, MISCELLANEOUS_OPERATION: 5 },
  TRUCK:                 { FIRE: 50, ROAD_ACCIDENT: 2, PERSONAL_INJURY: 2, MISCELLANEOUS_OPERATION: 2 },
  EMERGENCY_AMBULANCE:   { FIRE: 0,  ROAD_ACCIDENT: 20, PERSONAL_INJURY: 20, MISCELLANEOUS_OPERATION: 20 },
};
function efficiencyOf(vehicleType, emergencyType) {
  return (EFFICIENCY_BY_TYPE[vehicleType] || {})[emergencyType] || 0;
}

// Efficacité liquide ↔ type de feu (LiquidType.java fireEfficiencyMap)
const LIQUID_FIRE_EFFICIENCY = {
  ALL:            { A: 0.1, B: 0.1, C: 0.1, D: 0.1, E: 0.1 },
  WATER:          { A: 0.8, B: 0.8, C: 0.0, D: 0.0, E: 0.0 },
  POWDER:         { A: 0.6, B: 0.6, C: 1.0, D: 0.0, E: 0.0 },
  SPECIAL_POWDER: { A: 0.0, B: 0.0, C: 0.0, D: 1.0, E: 0.0 },
  CARBON_DIOXIDE: { A: 0.0, B: 0.7, C: 0.0, D: 0.0, E: 1.0 },
  FOAM:           { A: 0.7, B: 1.0, C: 0.0, D: 0.0, E: 0.0 },
};
function fireClass(fireType) {
  // FireType : A | B_Alcohol | B_Gasoline | B_Plastics | C_Flammable_Gases | D_Metals | E_Electric
  return (fireType || 'A').charAt(0);
}
function liquidEfficiencyOf(liquidType, fireType) {
  return (LIQUID_FIRE_EFFICIENCY[liquidType] || {})[fireClass(fireType)] || 0;
}

// Couleur unique violet/rose pour tous nos véhicules
const VEHICLE_COLOR = '#c026d3'; // fuchsia-600
function colorFor(_id) {
  return VEHICLE_COLOR;
}

// ── Icônes ──────────────────────────────────────────────────
// Feux d'intensité ≤ 3 = on les laisse s'éteindre seuls → vert. Sinon → orange/rouge.
const LOW_INTENSITY_THRESHOLD = 3;
function fireIconFor(intensity) {
  const bg = intensity <= LOW_INTENSITY_THRESHOLD
    ? 'linear-gradient(135deg,#22c55e,#15803d)'
    : 'linear-gradient(135deg,#f97316,#dc2626)';
  return L.divIcon({
    html: `
      <div style="background:${bg};border-radius:50%;width:42px;height:42px;display:flex;align-items:center;justify-content:center;">
        <img src="../images/fire.svg" style="width:22px;height:22px;filter:brightness(0) invert(1);"/>
      </div>`,
    iconSize: [42, 42], iconAnchor: [21, 21], className: 'fire-marker', zIndexOffset: -1000
  });
}

function vehicleIconFor(color, type) {
  const src = type === 'EMERGENCY_AMBULANCE' ? '../images/ambulance.svg'
            : type === 'WATER_TENDERS'      ? '../images/tenders.svg'
            : '../images/fire-truck.svg';
  return L.divIcon({
    html: `
      <div style="background:rgba(255,255,255,0.5);border-radius:50%;width:38px;height:38px;display:flex;align-items:center;justify-content:center;border:2.5px solid ${color};backdrop-filter:blur(2px);">
        <img src="${src}" style="width:20px;height:20px;"/>
      </div>`,
    iconSize: [38, 38], iconAnchor: [19, 19], className: 'vehicle-marker'
  });
}

const facilityIcon = L.divIcon({
  html: `
    <div style="background:white;width:50px;height:50px;border-radius:12px;display:flex;align-items:center;justify-content:center;border:2.5px solid #22c55e;">
      <img src="../images/facility.svg" style="width:28px;height:28px;"/>
    </div>`,
  iconSize: [50, 50], iconAnchor: [25, 25], className: 'facility-marker'
});

// Événements (accidents / blessés / divers) — icône SVG avec couleur par type
const EVENT_META = {
  ROAD_ACCIDENT: { img: '../images/accident.svg', bg: 'linear-gradient(135deg,#facc15,#f97316)', label: 'Accident', emoji: '🚗' },
  PERSONAL_INJURY: { img: '../images/injury.svg', bg: 'linear-gradient(135deg,#ef4444,#b91c1c)', label: 'Blessé', emoji: '🩹' },
  MISCELLANEOUS_OPERATION: { img: null, bg: 'linear-gradient(135deg,#94a3b8,#475569)', label: 'Divers', emoji: '⚠️' },
};
function eventIconFor(type) {
  const meta = EVENT_META[type] || EVENT_META.MISCELLANEOUS_OPERATION;
  const content = meta.img
    ? `<img src="${meta.img}" style="width:22px;height:22px;filter:brightness(0) invert(1);"/>`
    : `<span style="font-size:20px;">${meta.emoji}</span>`;
  return L.divIcon({
    html: `
      <div style="background:${meta.bg};border-radius:50%;width:42px;height:42px;display:flex;align-items:center;justify-content:center;border:2px solid white;">
        ${content}
      </div>`,
    iconSize: [42, 42], iconAnchor: [21, 21], className: 'event-marker', zIndexOffset: -1000
  });
}

// ── State ───────────────────────────────────────────────────
let firesData = []; // rajouté pour afficher la liste des feux
const fireMarkerById = new Map();      // id -> marker
const eventMarkerById = new Map();     // id -> marker
const facilityMarkerById = new Map();  // id -> marker
let vehiclesCache = [];
const vehicleState = new Map();         // id -> { marker, trailPoints, trailLayers, color }

function syncMarkersById(items, store, getId, build, visible, onUpdate) {
  const seen = new Set();
  items.forEach(item => {
    const id = getId(item);
    seen.add(id);
    let marker = store.get(id);
    if (!marker) {
      marker = build(item);
      if (visible) marker.addTo(map);
      store.set(id, marker);
    } else if (onUpdate) {
      onUpdate(marker, item);
    }
  });
  for (const [id, marker] of store) {
    if (!seen.has(id)) {
      map.removeLayer(marker);
      store.delete(id);
    }
  }
}

// Layer toggles (UI filters)
const layerVisibility = { fires: true, vehicles: true, trails: true, facilities: true, events: true };

// Sous-filtres par type (aligné sur les enums backend)
const ALL_FIRE_TYPES = ['A', 'B_Gasoline', 'B_Alcohol', 'B_Plastics', 'C_Flammable_Gases', 'D_Metals', 'E_Electric'];
const ALL_VEHICLE_TYPES = ['CAR', 'FIRE_ENGINE', 'PUMPER_TRUCK', 'WATER_TENDERS', 'TURNTABLE_LADDER_TRUCK', 'TRUCK', 'EMERGENCY_AMBULANCE'];
const enabledFireTypes = new Set(ALL_FIRE_TYPES);
const enabledVehicleTypes = new Set(ALL_VEHICLE_TYPES);

// Filtres intensité / étendue (valeurs min/max courantes)
const fireFilter = { minIntensity: 0, maxIntensity: Infinity, minRange: 0, maxRange: Infinity };
const FIRE_LABELS = {
  A: 'A · Secs', B_Gasoline: 'B · Essence', B_Alcohol: 'B · Alcool', B_Plastics: 'B · Plastiques',
  C_Flammable_Gases: 'C · Gaz', D_Metals: 'D · Métaux', E_Electric: 'E · Élec',
};

// ── Stats / popups helpers ──────────────────────────────────
function setStat(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = value;
}

function vehiclePopup(v) {
  return `
    <div class="pop-title">🚒 Véhicule #${v.id}</div>
    <div class="pop-row"><span class="pop-label">Type</span><span class="pop-value">${v.type}</span></div>
    <div class="pop-row"><span class="pop-label">Liquide</span><span class="pop-value">${v.liquidType} (${Math.round(v.liquidQuantity)}L)</span></div>
    <div class="pop-row"><span class="pop-label">Carburant</span><span class="pop-value">${Math.round(v.fuelQuantity)}L</span></div>
    <div class="pop-row"><span class="pop-label">Équipage</span><span class="pop-value">${v.crewMember}</span></div>
  `;
}

function firePopup(f) {
  const injured = f.injuredPeopleDtoList || [];
  return `
    <div class="pop-title">🔥 Feu #${f.id}</div>
    <div class="pop-row"><span class="pop-label">Type</span><span class="pop-value">${f.type}</span></div>
    <div class="pop-row"><span class="pop-label">Intensité</span><span class="pop-value">${f.intensity.toFixed(2)}</span></div>
    <div class="pop-row"><span class="pop-label">Étendue</span><span class="pop-value">${f.range.toFixed(2)}</span></div>
    <div class="pop-row"><span class="pop-label">Blessés</span><span class="pop-value">${injured.length}</span></div>
    ${injuredPopupBlock(injured)}
    ${assignVehicleBlockFire(f.type, f.id, f.lat, f.lon)}
  `;
}

function distanceKm(a, b) {
  const dLat = (a.lat - b.lat) * 111;
  const dLon = (a.lon - b.lon) * 111 * Math.cos(a.lat * Math.PI / 180);
  return Math.sqrt(dLat * dLat + dLon * dLon);
}

function vehiclePickerRow(v, dist, effLabel, fn, targetId) {
  return `
    <div style="display:flex;align-items:center;gap:6px;padding:4px 0;font-size:11px;">
      <span style="font-weight:600;color:#0f172a;flex-shrink:0;">#${v.id}</span>
      <span style="color:#475569;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${v.type}</span>
      <span style="color:#64748b;font-size:10px;">${dist.toFixed(1)}km</span>
      <span style="background:#fef3c7;color:#92400e;border-radius:4px;padding:1px 5px;font-weight:600;font-size:10px;">${effLabel}</span>
      <button onclick="${fn}(${v.id}, ${targetId}, this)"
        style="background:#c026d3;color:white;border:none;border-radius:6px;padding:3px 8px;font-size:11px;font-weight:600;cursor:pointer;">
        →
      </button>
    </div>
  `;
}

// Sélecteur pour FEU : liquide compatible OU ambulance (utile pour gérer les blessés sur place)
function assignVehicleBlockFire(fireType, fireId, lat, lon) {
  const compatible = vehiclesCache
    .map(v => {
      const isAmbu = v.type === 'EMERGENCY_AMBULANCE';
      const eff = isAmbu ? 0 : liquidEfficiencyOf(v.liquidType, fireType);
      return { v, eff, isAmbu, dist: distanceKm({ lat, lon }, { lat: v.lat, lon: v.lon }) };
    })
    .filter(x => x.isAmbu || x.eff > 0)
    // Pompiers d'abord (triés par efficacité), ambulances ensuite (triées par distance)
    .sort((a, b) => {
      if (a.isAmbu !== b.isAmbu) return a.isAmbu ? 1 : -1;
      if (!a.isAmbu) return b.eff - a.eff || a.dist - b.dist;
      return a.dist - b.dist;
    });

  if (compatible.length === 0) {
    return `<div style="margin-top:10px;padding-top:8px;border-top:1px solid rgba(0,0,0,0.06);font-size:11px;color:#94a3b8;">Aucun véhicule compatible</div>`;
  }
  const items = compatible.slice(0, 10)
    .map(({ v, eff, isAmbu, dist }) => {
      const label = isAmbu ? '🚑' : `${v.liquidType} ×${eff.toFixed(1)}`;
      return vehiclePickerRow(v, dist, label, 'assignVehicleToFire', fireId);
    })
    .join('');
  return `
    <div style="margin-top:10px;padding-top:8px;border-top:1px solid rgba(0,0,0,0.06);">
      <div style="font-size:11px;font-weight:700;color:#0f172a;margin-bottom:4px;">Assigner un véhicule (${compatible.length})</div>
      <div style="max-height:180px;overflow-y:auto;">${items}</div>
    </div>
  `;
}

// Sélecteur pour ÉVÉNEMENT : seules les ambulances sont éligibles
function assignVehicleBlockEvent(emergencyType, eventId, lat, lon) {
  const compatible = vehiclesCache
    .filter(v => v.type === 'EMERGENCY_AMBULANCE')
    .map(v => ({ v, eff: efficiencyOf(v.type, emergencyType), dist: distanceKm({ lat, lon }, { lat: v.lat, lon: v.lon }) }))
    .filter(x => x.eff > 0)
    .sort((a, b) => b.eff - a.eff || a.dist - b.dist);

  if (compatible.length === 0) {
    return `<div style="margin-top:10px;padding-top:8px;border-top:1px solid rgba(0,0,0,0.06);font-size:11px;color:#94a3b8;">Aucun véhicule compatible</div>`;
  }
  const items = compatible.slice(0, 10)
    .map(({ v, eff, dist }) => vehiclePickerRow(v, dist, `×${eff}`, 'assignVehicleToEvent', eventId))
    .join('');
  return `
    <div style="margin-top:10px;padding-top:8px;border-top:1px solid rgba(0,0,0,0.06);">
      <div style="font-size:11px;font-weight:700;color:#0f172a;margin-bottom:4px;">Assigner un véhicule (${compatible.length})</div>
      <div style="max-height:180px;overflow-y:auto;">${items}</div>
    </div>
  `;
}

window.assignVehicleToFire = async function(vehicleId, fireId, btn) {
  if (btn) { btn.disabled = true; btn.textContent = '...'; }
  try {
    await dispatchVehicleToFire(vehicleId, fireId);
    if (btn) btn.textContent = '✓';
  } catch (e) {
    console.error(e);
    if (btn) { btn.textContent = '✗'; btn.disabled = false; }
  }
};

window.assignVehicleToEvent = async function(vehicleId, eventId, btn) {
  if (btn) { btn.disabled = true; btn.textContent = '...'; }
  try {
    await dispatchVehicleToEvent(vehicleId, eventId);
    if (btn) btn.textContent = '✓';
  } catch (e) {
    console.error(e);
    if (btn) { btn.textContent = '✗'; btn.disabled = false; }
  }
};

function injuredPopupBlock(injured) {
  if (!injured || injured.length === 0) return '';
  const items = injured.map(p => {
    const inj = p.injuryDto || {};
    const type = inj.injuryType || '—';
    const intensity = inj.intensity != null ? inj.intensity.toFixed(2) : '—';
    const onVeh = p.vDto ? ` · 🚒 #${p.vDto.id}` : '';
    return `
      <div style="border-top:1px solid rgba(0,0,0,0.06);padding:6px 0;font-size:11px;">
        <div style="font-weight:600;color:#0f172a;">👤 #${p.id} — ${type}${onVeh}</div>
        <div style="color:#64748b;margin-top:2px;">
          <span>🔥 Intensité <b style="color:#0f172a;">${intensity}</b></span>
        </div>
      </div>`;
  }).join('');
  return `<div style="margin-top:8px;max-height:220px;overflow-y:auto;">${items}</div>`;
}

function eventPopup(ev) {
  const meta = EVENT_META[ev.eventType] || EVENT_META.MISCELLANEOUS_OPERATION;
  const count = (ev.injuredPeopleDtoList || []).length;
  return `
    <div class="pop-title">${meta.emoji} ${meta.label} #${ev.id}</div>
    <div class="pop-row"><span class="pop-label">Type</span><span class="pop-value">${ev.eventType}</span></div>
    <div class="pop-row"><span class="pop-label">Intensité</span><span class="pop-value">${ev.intensity.toFixed(2)}</span></div>
    <div class="pop-row"><span class="pop-label">Étendue</span><span class="pop-value">${ev.range.toFixed(2)}</span></div>
    <div class="pop-row"><span class="pop-label">Blessés</span><span class="pop-value">${count}</span></div>
    ${injuredPopupBlock(ev.injuredPeopleDtoList)}
    ${assignVehicleBlockEvent(ev.eventType, ev.id, ev.lat, ev.lon)}
  `;
}

function facilityPopup(f) {
  // Compte identique à la page Véhicules (somme spaceUsedInFacility + somme crewMember)
  const vehicles = vehiclesCache.filter(v => v.facilityRefID === f.id);
  const vehCount = vehicles.reduce((sum, v) => sum + (VEHICLE_SPACE_BY_TYPE[v.type] || 0), 0);
  const crewCount = vehicles.reduce((sum, v) => sum + (v.crewMember || 0), 0);
  const vehWarn = vehCount > f.maxVehicleSpace ? 'color:#dc2626' : '';
  const crewWarn = crewCount > f.peopleCapacity ? 'color:#dc2626' : '';
  return `
    <div class="pop-title">🏠 ${f.name} <span style="color:#94a3b8;font-weight:500;">#${f.id}</span></div>
    <div class="pop-row"><span class="pop-label">🚒 Véhicules</span><span class="pop-value" style="${vehWarn}">${vehCount} / ${f.maxVehicleSpace}</span></div>
    <div class="pop-row"><span class="pop-label">👨‍🚒 Équipage</span><span class="pop-value" style="${crewWarn}">${crewCount} / ${f.peopleCapacity}</span></div>
  `;
}

// ── Panneau liste des feux ──────────────────────────────────
function renderFireList() {
  /*
    Affiche la liste des feux dans le panneau latéral, triés par intensité décroissante.
    Chaque feu affiche son ID, son type et son intensité, avec une couleur verte si l'intensité est ≤ 3 (feu "faible" qu'on laisse s'éteindre seul) ou rouge sinon (feu "fort" à prioriser).
    Cliquer sur un feu dans la liste centre la carte dessus et ouvre son popup.
   */
  const body = document.getElementById('fires-list-body');
  const badge = document.getElementById('fires-list-badge');
  if (!body) return;
  if (badge) badge.textContent = firesData.length; // mise à jour du badge avec le nombre de feux
  if (firesData.length === 0) {
    body.innerHTML = '<div class="fires-list-empty">Aucun feu actif</div>';
    return;
  }
  const sorted = [...firesData].sort((a, b) => b.intensity - a.intensity); // tri par intensité décroissante
  body.innerHTML = sorted.map(f => {
    const high = f.intensity > LOW_INTENSITY_THRESHOLD;
    const color  = high ? '#dc2626' : '#16a34a';
    const bg     = high ? '#fef2f2' : '#f0fdf4';
    const border = high ? '#fca5a5' : '#86efac';
    // couleur rouge pour feux forts, vert pour feux faibles, avec un badge d'intensité ⚡ et un style de carte pour différencier les feux faibles (qu'on laisse s'éteindre seuls) des feux forts (à prioriser)
    return `
      <div class="fire-list-item" onclick="zoomToFire(${f.id})" style="border-color:${border};background:${bg};"> 
        <span class="fire-list-id" style="color:${color};">#${f.id}</span>
        <span class="fire-list-info">${f.type}</span>
        <span class="fire-list-intensity" style="color:${color};">⚡${f.intensity.toFixed(1)}</span>
      </div>`;
  }).join('');
}

function zoomToFire(id) { // centrer la carte sur un feu cliqué dans le panneau liste
  const marker = fireMarkerById.get(id); // on suppose que le marker existe encore (si le feu est dans la liste, il devrait être dans les markers), mais on vérifie quand même pour éviter les erreurs si jamais
  if (!marker) return;
  map.flyTo(marker.getLatLng(), 17, { duration: 1.2 });
  setTimeout(() => marker.openPopup(), 1300);
}

function toggleFiresPanel() { // pour le bouton d'ouverture du panneau liste des feux
  document.getElementById('fires-list-panel').classList.toggle('collapsed');
}

// ── Feux ────────────────────────────────────────────────────
async function fetchFires() {
  try {
    const res = await getFires();
    firesData = res.data; // mise à jour du cache global des feux pour le panneau liste
    syncMarkersById(
      res.data,
      fireMarkerById,
      f => f.id,
      f => {
        const marker = L.marker([f.lat, f.lon], { icon: fireIconFor(f.intensity) })
          .bindPopup(firePopup(f), { maxWidth: 320 });
        marker._isLowIntensity = f.intensity <= LOW_INTENSITY_THRESHOLD;
        marker._fireType = f.type;
        marker._intensity = f.intensity;
        marker._range = f.range;
        return marker;
      },
      false, // visibilité gérée par applyFireVisibility juste après (prend en compte le sous-filtre par type)
      (marker, f) => {
        marker.setLatLng([f.lat, f.lon]);
        marker.getPopup().setContent(firePopup(f));
        marker._fireType = f.type;
        marker._intensity = f.intensity;
        marker._range = f.range;
        const lowNow = f.intensity <= LOW_INTENSITY_THRESHOLD;
        if (marker._isLowIntensity !== lowNow) {
          marker.setIcon(fireIconFor(f.intensity));
          marker._isLowIntensity = lowNow;
        }
      }
    );
    applyFireVisibility();
    setStat('stat-fires', res.data.length);
    setStat('filter-fires-count', res.data.length);
    updateFireFilterCounts(); // met à jour les compteurs sans recréer les sliders
    renderFireList();
  } catch (err) { console.error(err); }
}

// ── Événements (accidents / blessés / divers, FIRE est géré par fetchFires) ─
async function fetchEvents() {
  try {
    const res = await getEvents();
    const filtered = res.data.filter(ev => ev.eventType !== 'FIRE');
    syncMarkersById(
      filtered,
      eventMarkerById,
      ev => ev.id,
      ev => L.marker([ev.lat, ev.lon], { icon: eventIconFor(ev.eventType) })
              .bindPopup(eventPopup(ev), { maxWidth: 320 }),
      layerVisibility.events,
      (marker, ev) => {
        marker.setLatLng([ev.lat, ev.lon]);
        marker.getPopup().setContent(eventPopup(ev));
      }
    );
    setStat('filter-events-count', filtered.length);
    setStat('stat-events', filtered.length);
  } catch (err) { console.error(err); }
}

// ── Casernes ────────────────────────────────────────────────
async function fetchFacilities() {
  try {
    const res = await getFacilities();
    syncMarkersById(
      res.data,
      facilityMarkerById,
      f => f.id,
      f => L.marker([f.lat, f.lon], { icon: facilityIcon })
             .bindPopup(() => facilityPopup(f)),
      layerVisibility.facilities,
      (marker, f) => marker.setLatLng([f.lat, f.lon])
    );
    setStat('stat-facilities', res.data.length);
    setStat('filter-facilities-count', res.data.length);
  } catch (err) { console.error(err); }
}

// ── Véhicules + trails ──────────────────────────────────────
function trailDotFor(lat, lon, color, opacity) {
  return L.circleMarker([lat, lon], {
    radius: 2.5, color, fillColor: color,
    fillOpacity: opacity, opacity, weight: 0, interactive: false,
  });
}

function updateVehicleTrail(state, lat, lon, color) {
  const pts = state.trailPoints;
  const last = pts[pts.length - 1];
  if (last && Math.abs(last.lat - lat) < TRAIL_MIN_DIST && Math.abs(last.lon - lon) < TRAIL_MIN_DIST) return;
  pts.push({ lat, lon });
  if (pts.length > TRAIL_MAX_POINTS) pts.shift();

  state.trailLayers.forEach(l => map.removeLayer(l));
  const showTrail = layerVisibility.trails && (state.vehicleType == null || enabledVehicleTypes.has(state.vehicleType));
  state.trailLayers = pts.map((p, i) => {
    const opacity = 0.15 + 0.55 * (i / pts.length);
    const dot = trailDotFor(p.lat, p.lon, color, opacity);
    if (showTrail) dot.addTo(map);
    return dot;
  });
}

async function fetchVehicles() {
  try {
    const res = await getVehicles();
    vehiclesCache = res.data;
    const seen = new Set();
    let onMission = 0;
    const now = performance.now();

    res.data.forEach(vehicle => {
      seen.add(vehicle.id);
      const color = colorFor(vehicle.id);
      const popup = vehiclePopup(vehicle);

      let state = vehicleState.get(vehicle.id);
      if (!state) {
        const marker = L.marker([vehicle.lat, vehicle.lon], { icon: vehicleIconFor(color, vehicle.type), pane: 'vehiclePane' }).bindPopup(popup);
        state = {
          marker, trailPoints: [], trailLayers: [], color, vehicleType: vehicle.type,
          prevLat: vehicle.lat, prevLon: vehicle.lon, prevTime: now,
          targetLat: vehicle.lat, targetLon: vehicle.lon,
          displayLat: vehicle.lat, displayLon: vehicle.lon,
          lastTrailSampleAt: 0,
        };
        vehicleState.set(vehicle.id, state);
        if (isVehicleVisible(state)) marker.addTo(map);
      } else {
        state.vehicleType = vehicle.type;
        // Snapshot la position interpolée actuelle comme nouveau "prev", target = nouvelle position serveur
        state.prevLat = state.displayLat;
        state.prevLon = state.displayLon;
        state.prevTime = now;
        state.targetLat = vehicle.lat;
        state.targetLon = vehicle.lon;
        state.marker.getPopup().setContent(popup);
      }
    });

    // Cleanup véhicules disparus
    for (const [id, state] of vehicleState) {
      if (!seen.has(id)) {
        map.removeLayer(state.marker);
        state.trailLayers.forEach(l => map.removeLayer(l));
        vehicleState.delete(id);
      }
    }

    applyVehicleVisibility();
    renderSubVehicleFilters();
    // Stats: "en mission" = pas dans la tolérance d'une caserne
    onMission = countOnMission();
    setStat('stat-vehicles', res.data.length);
    setStat('stat-mission', onMission);
    setStat('filter-vehicles-count', res.data.length);
  } catch (err) { console.error(err); }
}

function countOnMission() {
  const TOL = 0.0005;
  if (vehiclesCache.length === 0) return 0;
  const facCoords = Array.from(facilityMarkerById.values()).map(m => m.getLatLng());
  return vehiclesCache.filter(v => {
    return !facCoords.some(c =>
      Math.abs(v.lat - c.lat) < TOL && Math.abs(v.lon - c.lng) < TOL);
  }).length;
}

// ── Visibilité effective d'un marker (couche + type) ─────────
function isFireVisible(marker) {
  if (!layerVisibility.fires) return false;
  if (!enabledFireTypes.has(marker._fireType)) return false;
  if (marker._intensity < fireFilter.minIntensity || marker._intensity > fireFilter.maxIntensity) return false;
  if (marker._range < fireFilter.minRange || marker._range > fireFilter.maxRange) return false;
  return true;
}
function isVehicleVisible(state) {
  return layerVisibility.vehicles && enabledVehicleTypes.has(state.vehicleType);
}
function applyFireVisibility() {
  fireMarkerById.forEach(m => isFireVisible(m) ? m.addTo(map) : map.removeLayer(m));
}
function applyVehicleVisibility() {
  vehicleState.forEach(s => {
    const vis = isVehicleVisible(s);
    vis ? s.marker.addTo(map) : map.removeLayer(s.marker);
    s.trailLayers.forEach(l => (vis && layerVisibility.trails) ? l.addTo(map) : map.removeLayer(l));
  });
}

// ── Toggle des layers ───────────────────────────────────────
function toggleLayer(name) {
  layerVisibility[name] = !layerVisibility[name];
  document.getElementById(`filter-${name}`).classList.toggle('off', !layerVisibility[name]);

  if (name === 'fires') {
    applyFireVisibility();
  } else if (name === 'events') {
    eventMarkerById.forEach(m => layerVisibility.events ? m.addTo(map) : map.removeLayer(m));
  } else if (name === 'facilities') {
    facilityMarkerById.forEach(m => layerVisibility.facilities ? m.addTo(map) : map.removeLayer(m));
  } else if (name === 'vehicles') {
    applyVehicleVisibility();
  } else if (name === 'trails') {
    vehicleState.forEach(s => s.trailLayers.forEach(l => (layerVisibility.trails && isVehicleVisible(s)) ? l.addTo(map) : map.removeLayer(l)));
    document.getElementById('filter-trails-count').textContent = layerVisibility.trails ? 'on' : 'off';
  }
}

// ── Sous-filtres : expand + toggle par type ──────────────────
function toggleSubFilters(group) {
  const panel = document.getElementById(`sub-${group}`);
  const chevron = document.getElementById(`filter-${group}-chevron`);
  const wasHidden = panel.classList.contains('hidden');
  panel.classList.toggle('hidden');
  chevron.classList.toggle('expanded', wasHidden);
  // Render complet seulement à l'ouverture (pour initialiser les sliders proprement)
  if (wasHidden) {
    if (group === 'fires') renderSubFireFilters();
    if (group === 'vehicles') renderSubVehicleFilters();
  }
}

function toggleFireType(type) {
  if (enabledFireTypes.has(type)) enabledFireTypes.delete(type);
  else enabledFireTypes.add(type);
  applyFireVisibility();
  renderSubFireFilters();
}
function toggleVehicleType(type) {
  if (enabledVehicleTypes.has(type)) enabledVehicleTypes.delete(type);
  else enabledVehicleTypes.add(type);
  applyVehicleVisibility();
  renderSubVehicleFilters();
}
window.toggleSubFilters = toggleSubFilters;
window.toggleFireType = toggleFireType;
window.toggleVehicleType = toggleVehicleType;

function fireObsMax() {
  let obsMaxIntensity = 0, obsMaxRange = 0;
  fireMarkerById.forEach(m => {
    if ((m._intensity || 0) > obsMaxIntensity) obsMaxIntensity = m._intensity;
    if ((m._range || 0) > obsMaxRange) obsMaxRange = m._range;
  });
  return { obsMaxIntensity: Math.ceil(obsMaxIntensity) || 100, obsMaxRange: Math.ceil(obsMaxRange) || 100 };
}

function updateDualRangeTrack(wrapId, min, max, obsMax) {
  const fill = document.getElementById(wrapId + '-fill');
  if (!fill) return;
  const pMin = (min / obsMax) * 100;
  const pMax = (max / obsMax) * 100;
  fill.style.left = pMin + '%';
  fill.style.width = (pMax - pMin) + '%';
}

// Render initial (structure HTML complète) — appelé une seule fois à l'ouverture
function renderSubFireFilters() {
  const panel = document.getElementById('sub-fires');
  if (!panel) return;

  const { obsMaxIntensity, obsMaxRange } = fireObsMax();
  const counts = {};
  for (const t of ALL_FIRE_TYPES) counts[t] = 0;
  fireMarkerById.forEach(m => { if (counts[m._fireType] != null) counts[m._fireType]++; });

  const curMinI = Math.min(fireFilter.minIntensity, obsMaxIntensity);
  const curMaxI = fireFilter.maxIntensity === Infinity ? obsMaxIntensity : Math.min(fireFilter.maxIntensity, obsMaxIntensity);
  const curMinR = Math.min(fireFilter.minRange, obsMaxRange);
  const curMaxR = fireFilter.maxRange === Infinity ? obsMaxRange : Math.min(fireFilter.maxRange, obsMaxRange);

  const typeChips = ALL_FIRE_TYPES.map(t => {
    const off = !enabledFireTypes.has(t);
    return `<span class="sub-chip ${off ? 'off' : ''}" id="chip-fire-${t}" onclick="toggleFireType('${t}')">
      ${FIRE_LABELS[t] || t}<span class="sub-count" id="count-fire-${t}">${counts[t]}</span>
    </span>`;
  }).join('');

  panel.innerHTML = `
    <div style="width:100%;padding:4px 0 2px;">${typeChips}</div>
    <div class="fire-range-filter">
      <span class="range-label">⚡ Int.</span>
      <div class="dual-range-wrap" id="wrap-intensity">
        <div class="dual-range-track"></div>
        <div class="dual-range-fill" id="wrap-intensity-fill"></div>
        <input type="range" min="0" max="${obsMaxIntensity}" step="0.5" value="${curMinI}"
          oninput="updateFireRange('minIntensity', this.value, ${obsMaxIntensity})"
          class="range-slider range-min" id="slider-intensity-min">
        <input type="range" min="0" max="${obsMaxIntensity}" step="0.5" value="${curMaxI}"
          oninput="updateFireRange('maxIntensity', this.value, ${obsMaxIntensity})"
          class="range-slider range-max" id="slider-intensity-max">
      </div>
      <span class="range-values" id="lbl-intensity">${curMinI.toFixed(0)}–${curMaxI.toFixed(0)}</span>
    </div>
    <div class="fire-range-filter">
      <span class="range-label">📐 Ét.</span>
      <div class="dual-range-wrap" id="wrap-range">
        <div class="dual-range-track"></div>
        <div class="dual-range-fill" id="wrap-range-fill"></div>
        <input type="range" min="0" max="${obsMaxRange}" step="0.5" value="${curMinR}"
          oninput="updateFireRange('minRange', this.value, ${obsMaxRange})"
          class="range-slider range-min" id="slider-range-min">
        <input type="range" min="0" max="${obsMaxRange}" step="0.5" value="${curMaxR}"
          oninput="updateFireRange('maxRange', this.value, ${obsMaxRange})"
          class="range-slider range-max" id="slider-range-max">
      </div>
      <span class="range-values" id="lbl-range">${curMinR.toFixed(0)}–${curMaxR.toFixed(0)}</span>
    </div>
  `;
  updateDualRangeTrack('wrap-intensity', curMinI, curMaxI, obsMaxIntensity);
  updateDualRangeTrack('wrap-range', curMinR, curMaxR, obsMaxRange);
}

// Mise à jour légère (compteurs chips uniquement) — appelé à chaque fetchFires
function updateFireFilterCounts() {
  const counts = {};
  for (const t of ALL_FIRE_TYPES) counts[t] = 0;
  fireMarkerById.forEach(m => { if (counts[m._fireType] != null) counts[m._fireType]++; });
  for (const t of ALL_FIRE_TYPES) {
    const el = document.getElementById(`count-fire-${t}`);
    if (el) el.textContent = counts[t];
  }
}

window.updateFireRange = function(key, value, obsMax) {
  fireFilter[key] = key.startsWith('max') && parseFloat(value) >= obsMax ? Infinity : parseFloat(value);
  if (fireFilter.minIntensity > fireFilter.maxIntensity) fireFilter.minIntensity = fireFilter.maxIntensity;
  if (fireFilter.minRange > fireFilter.maxRange) fireFilter.minRange = fireFilter.maxRange;
  applyFireVisibility();
  const { obsMaxIntensity, obsMaxRange } = fireObsMax();
  const iMax = fireFilter.maxIntensity === Infinity ? obsMaxIntensity : fireFilter.maxIntensity;
  const rMax = fireFilter.maxRange === Infinity ? obsMaxRange : fireFilter.maxRange;
  if (key.includes('ntensity')) {
    const el = document.getElementById('lbl-intensity');
    if (el) el.textContent = `${fireFilter.minIntensity.toFixed(0)}–${iMax.toFixed(0)}`;
    updateDualRangeTrack('wrap-intensity', fireFilter.minIntensity, iMax, obsMaxIntensity);
  } else {
    const el = document.getElementById('lbl-range');
    if (el) el.textContent = `${fireFilter.minRange.toFixed(0)}–${rMax.toFixed(0)}`;
    updateDualRangeTrack('wrap-range', fireFilter.minRange, rMax, obsMaxRange);
  }
};

function renderSubVehicleFilters() {
  const panel = document.getElementById('sub-vehicles');
  if (!panel) return;
  const counts = {};
  for (const t of ALL_VEHICLE_TYPES) counts[t] = 0;
  vehiclesCache.forEach(v => { if (counts[v.type] != null) counts[v.type]++; });
  panel.innerHTML = ALL_VEHICLE_TYPES.map(t => {
    const off = !enabledVehicleTypes.has(t);
    const icon = t === 'EMERGENCY_AMBULANCE' ? '🚑' : t === 'WATER_TENDERS' ? '🚛' : '🚒';
    return `<span class="sub-chip ${off ? 'off' : ''}" onclick="toggleVehicleType('${t}')">
      ${icon} ${t.replace(/_/g, ' ').toLowerCase()}<span class="sub-count">${counts[t]}</span>
    </span>`;
  }).join('');
}

// ── Boucle d'animation : interpole entre prev → target à ~60 fps natif ─
function animationLoop() {
  const now = performance.now();
  for (const state of vehicleState.values()) {
    const elapsed = now - state.prevTime;
    const t = Math.min(1, elapsed / VEHICLE_REFRESH_MS);
    state.displayLat = state.prevLat + (state.targetLat - state.prevLat) * t;
    state.displayLon = state.prevLon + (state.targetLon - state.prevLon) * t;
    state.marker.setLatLng([state.displayLat, state.displayLon]);

    if (now - state.lastTrailSampleAt >= TRAIL_SAMPLE_MS) {
      state.lastTrailSampleAt = now;
      updateVehicleTrail(state, state.displayLat, state.displayLon, state.color);
    }
  }
  // Stats temps réel (cheap, pas d'API call)
  setStat('stat-mission', countOnMission());
  requestAnimationFrame(animationLoop);
}

// ── Boot ────────────────────────────────────────────────────
fetchFires(); // premier fetch à part pour pouvoir afficher la liste des feux dans le panneau latéral, qui est un élément clé de notre UI et doit être mis à jour dès que possible (avant même les véhicules, pour avoir les stats et la liste des feux à dispo immédiatement), tandis que les véhicules peuvent se charger juste après sans que ce soit gênant pour l'expérience utilisateur
fetchEvents();
fetchFacilities().then(fetchVehicles); // casernes d'abord pour stat "en mission"

setInterval(fetchVehicles, VEHICLE_REFRESH_MS);
setInterval(() => { fetchFires(); fetchEvents(); fetchFacilities(); }, STATIC_REFRESH_MS);
requestAnimationFrame(animationLoop);
