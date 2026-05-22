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
  `;
}

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

// ── Feux ────────────────────────────────────────────────────
async function fetchFires() {
  try {
    const res = await getFires();
    syncMarkersById(
      res.data,
      fireMarkerById,
      f => f.id,
      f => {
        const marker = L.marker([f.lat, f.lon], { icon: fireIconFor(f.intensity) })
          .bindPopup(firePopup(f), { maxWidth: 320 });
        marker._isLowIntensity = f.intensity <= LOW_INTENSITY_THRESHOLD;
        return marker;
      },
      layerVisibility.fires,
      (marker, f) => {
        marker.setLatLng([f.lat, f.lon]);
        marker.getPopup().setContent(firePopup(f));
        const lowNow = f.intensity <= LOW_INTENSITY_THRESHOLD;
        if (marker._isLowIntensity !== lowNow) {
          marker.setIcon(fireIconFor(f.intensity));
          marker._isLowIntensity = lowNow;
        }
      }
    );
    setStat('stat-fires', res.data.length);
    setStat('filter-fires-count', res.data.length);
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
fetchFires();
fetchEvents();
fetchFacilities().then(fetchVehicles); // casernes d'abord pour stat "en mission"

setInterval(fetchVehicles, VEHICLE_REFRESH_MS);
setInterval(() => { fetchFires(); fetchEvents(); fetchFacilities(); }, STATIC_REFRESH_MS);
