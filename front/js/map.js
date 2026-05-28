const map = L.map('map', { zoomControl: true }).setView([45.75, 4.85], 13);

// Tile layer — Carto Voyager (plus moderne / coloré que light_all)
L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
  attribution: '© OpenStreetMap · © CARTO',
  subdomains: 'abcd',
  maxZoom: 19,
}).addTo(map);

// ── Refresh rates ───────────────────────────────────────────
const VEHICLE_REFRESH_MS = 120;
const STATIC_REFRESH_MS  = 2000;

// ── Trails ──────────────────────────────────────────────────
const TRAIL_MAX_POINTS = 150;
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
      <div style="background:${bg};border-radius:50%;width:38px;height:38px;display:flex;align-items:center;justify-content:center;">
        <img src="../images/fire.svg" style="width:20px;height:20px;filter:brightness(0) invert(1);"/>
      </div>`,
    iconSize: [38, 38], iconAnchor: [19, 19], className: 'fire-marker'
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
    <div style="background:white;width:42px;height:42px;border-radius:12px;display:flex;align-items:center;justify-content:center;border:2.5px solid #22c55e;">
      <img src="../images/facility.svg" style="width:24px;height:24px;"/>
    </div>`,
  iconSize: [42, 42], iconAnchor: [21, 21], className: 'facility-marker'
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
    ? `<img src="${meta.img}" style="width:20px;height:20px;filter:brightness(0) invert(1);"/>`
    : `<span style="font-size:18px;">${meta.emoji}</span>`;
  return L.divIcon({
    html: `
      <div style="background:${meta.bg};border-radius:50%;width:36px;height:36px;display:flex;align-items:center;justify-content:center;border:2px solid white;">
        ${content}
      </div>`,
    iconSize: [36, 36], iconAnchor: [18, 18], className: 'event-marker'
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
          .bindPopup(firePopup(f), { maxWidth: 320 }); // ouverture du popup à 320px de large pour éviter les problèmes de mise en page avec les listes de blessés trop longues
        marker._isLowIntensity = f.intensity <= LOW_INTENSITY_THRESHOLD; // on stocke cette info dans le marker pour éviter de recalculer l'icône à chaque update (seule la transition entre "faible" et "fort" nécessite un changement d'icône)
        return marker;
      },
      layerVisibility.fires, // on ajoute les nouveaux feux à la carte seulement si le layer est visible
      (marker, f) => {
        marker.setLatLng([f.lat, f.lon]); // mise à jour de la position du feu
        marker.getPopup().setContent(firePopup(f)); // mise à jour du contenu du popup (intensité, blessés, etc.)
        const lowNow = f.intensity <= LOW_INTENSITY_THRESHOLD; // vérification si le feu est maintenant considéré comme "faible" ou "fort"
        if (marker._isLowIntensity !== lowNow) {
          marker.setIcon(fireIconFor(f.intensity));
          marker._isLowIntensity = lowNow;
        }
      }
    );
    setStat('stat-fires', res.data.length);
    setStat('filter-fires-count', res.data.length);
    renderFireList(); // mise à jour du panneau liste des feux à chaque refresh (pour refléter les changements d'intensité et les nouveaux feux)
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
  state.trailLayers = pts.map((p, i) => {
    const opacity = 0.15 + 0.55 * (i / pts.length);
    const dot = trailDotFor(p.lat, p.lon, color, opacity);
    if (layerVisibility.trails) dot.addTo(map);
    return dot;
  });
}

async function fetchVehicles() {
  try {
    const res = await getVehicles();
    vehiclesCache = res.data;
    const seen = new Set();
    let onMission = 0;

    res.data.forEach(vehicle => {
      seen.add(vehicle.id);
      const color = colorFor(vehicle.id);
      const popup = vehiclePopup(vehicle);

      let state = vehicleState.get(vehicle.id);
      if (!state) {
        const marker = L.marker([vehicle.lat, vehicle.lon], { icon: vehicleIconFor(color, vehicle.type) }).bindPopup(popup);
        if (layerVisibility.vehicles) marker.addTo(map);
        state = { marker, trailPoints: [], trailLayers: [], color };
        vehicleState.set(vehicle.id, state);
      } else {
        state.marker.setLatLng([vehicle.lat, vehicle.lon]);
        state.marker.getPopup().setContent(popup);
      }

      // "En mission" = pas à sa caserne d'attache (approximation distance > 50m)
      updateVehicleTrail(state, vehicle.lat, vehicle.lon, color);
    });

    // Cleanup véhicules disparus
    for (const [id, state] of vehicleState) {
      if (!seen.has(id)) {
        map.removeLayer(state.marker);
        state.trailLayers.forEach(l => map.removeLayer(l));
        vehicleState.delete(id);
      }
    }

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

// ── Toggle des layers ───────────────────────────────────────
function toggleLayer(name) {
  layerVisibility[name] = !layerVisibility[name];
  document.getElementById(`filter-${name}`).classList.toggle('off', !layerVisibility[name]);

  if (name === 'fires') {
    fireMarkerById.forEach(m => layerVisibility.fires ? m.addTo(map) : map.removeLayer(m));
  } else if (name === 'events') {
    eventMarkerById.forEach(m => layerVisibility.events ? m.addTo(map) : map.removeLayer(m));
  } else if (name === 'facilities') {
    facilityMarkerById.forEach(m => layerVisibility.facilities ? m.addTo(map) : map.removeLayer(m));
  } else if (name === 'vehicles') {
    vehicleState.forEach(s => layerVisibility.vehicles ? s.marker.addTo(map) : map.removeLayer(s.marker));
  } else if (name === 'trails') {
    vehicleState.forEach(s => s.trailLayers.forEach(l => layerVisibility.trails ? l.addTo(map) : map.removeLayer(l)));
    document.getElementById('filter-trails-count').textContent = layerVisibility.trails ? 'on' : 'off';
  }
}

// ── Boot ────────────────────────────────────────────────────
fetchFires(); // premier fetch à part pour pouvoir afficher la liste des feux dans le panneau latéral, qui est un élément clé de notre UI et doit être mis à jour dès que possible (avant même les véhicules, pour avoir les stats et la liste des feux à dispo immédiatement), tandis que les véhicules peuvent se charger juste après sans que ce soit gênant pour l'expérience utilisateur
fetchEvents();
fetchFacilities().then(fetchVehicles); // casernes d'abord pour stat "en mission"

setInterval(fetchVehicles, VEHICLE_REFRESH_MS);
setInterval(() => { fetchFires(); fetchEvents(); fetchFacilities(); }, STATIC_REFRESH_MS);
