const FACILITY_TOLERANCE = 0.0005; // ~50m

// Doit rester aligné avec VehicleType.java
const VEHICLE_SPACE_BY_TYPE = {
  CAR: 2, FIRE_ENGINE: 4, PUMPER_TRUCK: 10, WATER_TENDERS: 10,
  TURNTABLE_LADDER_TRUCK: 15, TRUCK: 20, EMERGENCY_AMBULANCE: 4,
};
const LIQUID_CAPACITY_BY_TYPE = {
  CAR: 10, FIRE_ENGINE: 50, PUMPER_TRUCK: 1000, WATER_TENDERS: 1000,
  TURNTABLE_LADDER_TRUCK: 1000, TRUCK: 2000, EMERGENCY_AMBULANCE: 0,
};
const FUEL_CAPACITY_BY_TYPE = {
  CAR: 50, FIRE_ENGINE: 60, PUMPER_TRUCK: 500, WATER_TENDERS: 500,
  TURNTABLE_LADDER_TRUCK: 500, TRUCK: 500, EMERGENCY_AMBULANCE: 60,
};
// vehicleCrewCapacity côté Java (2e arg du constructeur VehicleType)
const CREW_CAPACITY_BY_TYPE = {
  CAR: 2, FIRE_ENGINE: 4, PUMPER_TRUCK: 6, WATER_TENDERS: 3,
  TURNTABLE_LADDER_TRUCK: 6, TRUCK: 8, EMERGENCY_AMBULANCE: 2,
};
const VEHICLE_ICON = {
  CAR: '🚓', FIRE_ENGINE: '🚒', PUMPER_TRUCK: '🚛', WATER_TENDERS: '🚛',
  TURNTABLE_LADDER_TRUCK: '🪜', TRUCK: '🚚', EMERGENCY_AMBULANCE: '🚑',
};
const LIQUID_ICON = {
  ALL: '🌈', WATER: '💧', POWDER: '🟤', SPECIAL_POWDER: '⚪',
  CARBON_DIOXIDE: '💨', FOAM: '🫧',
};

function pct(cur, max) {
  if (!max || max <= 0) return 0;
  return Math.min(100, Math.max(0, (cur / max) * 100));
}
function gaugeColor(p, overloaded) {
  if (overloaded) return 'bg-red-500';
  if (p >= 80) return 'bg-emerald-500';
  if (p >= 40) return 'bg-amber-500';
  return 'bg-rose-500';
}
function capacityGaugeColor(p) {
  if (p > 100) return 'bg-red-500';
  if (p >= 90) return 'bg-amber-500';
  return 'bg-emerald-500';
}

// Caches partagés
let facilitiesCache = [];
let vehiclesCache = [];

async function loadFacilities() {
  try {
    const res = await getFacilities();
    facilitiesCache = res.data;
    // Peuple le select du formulaire de création si présent
    const sel = document.getElementById('facilityRefID');
    if (sel) {
      const current = sel.value;
      sel.innerHTML = facilitiesCache
        .map(f => `<option value="${f.id}">${f.name} (#${f.id})</option>`)
        .join('');
      if (current) sel.value = current;
    }
  } catch (err) {
    console.error('Erreur chargement casernes:', err);
  }
}

// True si le véhicule est dans la tolérance d'AU MOINS une caserne
function isAtFacility(v) {
  return facilitiesCache.some(f =>
       Math.abs(v.lat - f.lat) < FACILITY_TOLERANCE
    && Math.abs(v.lon - f.lon) < FACILITY_TOLERANCE);
}

function facilityLabelOf(v) {
  const f = facilitiesCache.find(x => x.id === v.facilityRefID);
  return f ? `${f.name} (#${f.id})` : `#${v.facilityRefID}`;
}

function renderGlobalStats() {
  const container = document.getElementById('global-stats');
  if (!container) return;
  const total = vehiclesCache.length;
  const atFac = vehiclesCache.filter(isAtFacility).length;
  const onMission = total - atFac;
  const stat = (label, val) => `
    <div class="bg-white/15 backdrop-blur-sm rounded-xl px-4 py-2.5 min-w-[90px]">
      <div class="text-2xl font-extrabold leading-none">${val}</div>
      <div class="text-xs text-orange-100 mt-1 font-medium">${label}</div>
    </div>`;
  container.innerHTML = [
    stat('Total', total),
    stat('À la caserne', atFac),
    stat('En mission', onMission),
    stat('Casernes', facilitiesCache.length),
  ].join('');
}

function renderFacilityCapacity() {
  const container = document.getElementById('facility-capacity');
  if (!container) return;
  if (facilitiesCache.length === 0) {
    container.innerHTML = '<p class="text-slate-400">Aucune caserne</p>';
    return;
  }
  container.innerHTML = facilitiesCache.map(f => {
    const vehiclesOfFacility = vehiclesCache.filter(v => v.facilityRefID === f.id);
    const vehCount = vehiclesOfFacility.reduce((sum, v) => sum + (VEHICLE_SPACE_BY_TYPE[v.type] || 0), 0);
    const vehMax = f.maxVehicleSpace;
    const crewCount = vehiclesOfFacility.reduce((sum, v) => sum + (v.crewMember || 0), 0);
    const crewMax = f.peopleCapacity;
    const vehPct = pct(vehCount, vehMax);
    const crewPct = pct(crewCount, crewMax);
    const vehBar = capacityGaugeColor((vehCount / Math.max(vehMax, 1)) * 100);
    const crewBar = capacityGaugeColor((crewCount / Math.max(crewMax, 1)) * 100);
    const vehText = vehCount > vehMax ? 'text-red-600' : 'text-slate-700';
    const crewText = crewCount > crewMax ? 'text-red-600' : 'text-slate-700';
    return `
      <div class="card-hover bg-gradient-to-br from-slate-50 to-white border border-slate-200 rounded-xl p-4">
        <div class="flex items-start justify-between mb-3">
          <div>
            <p class="font-bold text-slate-800">${f.name}</p>
            <p class="text-xs text-slate-400 font-medium">#${f.id}</p>
          </div>
          <span class="text-2xl">🏠</span>
        </div>
        <div class="mb-3">
          <div class="flex justify-between text-xs font-semibold mb-1">
            <span class="text-slate-500">🚒 Véhicules</span>
            <span class="${vehText}">${vehCount} / ${vehMax}</span>
          </div>
          <div class="h-2 bg-slate-200 rounded-full overflow-hidden">
            <div class="progress-bar h-full ${vehBar} rounded-full" style="width: ${vehPct}%"></div>
          </div>
        </div>
        <div>
          <div class="flex justify-between text-xs font-semibold mb-1">
            <span class="text-slate-500">👨‍🚒 Équipage</span>
            <span class="${crewText}">${crewCount} / ${crewMax}</span>
          </div>
          <div class="h-2 bg-slate-200 rounded-full overflow-hidden">
            <div class="progress-bar h-full ${crewBar} rounded-full" style="width: ${crewPct}%"></div>
          </div>
        </div>
      </div>
    `;
  }).join('');
}

async function loadVehicles() {
  const [vRes, rRes] = await Promise.all([getVehicles(), getRecallMode()]);
  const vehicles = [...vRes.data].sort((a, b) => a.id - b.id);
  vehiclesCache = vehicles;
  const globalRecall = rRes.data.recallMode;
  const recalledIds = new Set(rRes.data.recalledIds || []);
  const list = document.getElementById('vehicle-list');

  // Synchronise l'apparence du bouton global avec l'état réel
  const globalBtn = document.getElementById('recall-btn');
  if (globalBtn) globalBtn.textContent = globalRecall ? '▶️ Reprendre le dispatch' : '🏠 Rappeler à la caserne';

  renderGlobalStats();
  renderFacilityCapacity();

  if (vehicles.length === 0) {
    list.innerHTML = '<div class="text-center py-12 text-slate-400"><div class="text-5xl mb-2">🚙</div><p>Aucun véhicule</p></div>';
    return;
  }

  list.innerHTML = vehicles.map(v => {
    const isRecalled = globalRecall || recalledIds.has(v.id);
    const atFacility = isAtFacility(v);

    const recallClass = isRecalled
      ? 'bg-emerald-500 hover:bg-emerald-600'
      : 'bg-blue-600 hover:bg-blue-700';
    const recallLabel = isRecalled ? '▶️ Reprendre' : '🏠 Caserne';

    const statusBadge = atFacility
      ? `<span class="inline-flex items-center gap-1.5 bg-emerald-100 text-emerald-700 text-xs font-semibold rounded-full px-2.5 py-1">
           <span class="w-1.5 h-1.5 bg-emerald-500 rounded-full"></span>À la caserne
         </span>`
      : `<span class="inline-flex items-center gap-1.5 bg-orange-100 text-orange-700 text-xs font-semibold rounded-full px-2.5 py-1">
           <span class="w-1.5 h-1.5 bg-orange-500 rounded-full animate-pulse"></span>En mission
         </span>`;

    const editBtn = atFacility
      ? `<button onclick="openEditModal(${v.id})" class="bg-slate-100 hover:bg-slate-200 text-slate-700 font-semibold rounded-lg px-3 py-1.5 text-sm transition-colors">✏️ Modifier</button>`
      : '';

    const liquidMax = LIQUID_CAPACITY_BY_TYPE[v.type] || 0;
    const fuelMax = FUEL_CAPACITY_BY_TYPE[v.type] || 0;
    const liquidPct = pct(v.liquidQuantity, liquidMax);
    const fuelPct = pct(v.fuelQuantity, fuelMax);
    const liquidColor = gaugeColor(liquidPct, false);
    const fuelColor = gaugeColor(fuelPct, false);
    const vIcon = VEHICLE_ICON[v.type] || '🚒';
    const lIcon = LIQUID_ICON[v.liquidType] || '💧';

    const liquidBar = liquidMax > 0
      ? `<div>
           <div class="flex justify-between text-xs font-semibold mb-1">
             <span class="text-slate-500">${lIcon} ${v.liquidType}</span>
             <span class="text-slate-700">${Math.round(v.liquidQuantity)} / ${liquidMax}L</span>
           </div>
           <div class="h-1.5 bg-slate-200 rounded-full overflow-hidden">
             <div class="progress-bar h-full ${liquidColor} rounded-full" style="width: ${liquidPct}%"></div>
           </div>
         </div>`
      : '';

    return `
      <div class="card-hover bg-white border border-slate-200 rounded-xl p-4">
        <div class="flex items-start justify-between gap-4 mb-3 flex-wrap">
          <div class="flex items-center gap-3">
            <div class="text-3xl">${vIcon}</div>
            <div>
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-bold text-slate-800">#${v.id}</span>
                <span class="text-sm font-semibold text-slate-500">${v.type}</span>
                ${statusBadge}
              </div>
              <p class="text-xs text-slate-400 mt-0.5">🏠 ${facilityLabelOf(v)} · 👨‍🚒 ${v.crewMember}</p>
            </div>
          </div>
          <div class="flex gap-2 flex-wrap">
            ${editBtn}
            <button onclick="toggleRecallOne(${v.id}, ${isRecalled})" class="${recallClass} text-white font-semibold rounded-lg px-3 py-1.5 text-sm shadow-sm transition-colors">
              ${recallLabel}
            </button>
            <button onclick="removeVehicle(${v.id})" class="bg-red-500 hover:bg-red-600 text-white font-semibold rounded-lg px-3 py-1.5 text-sm shadow-sm transition-colors">
              🗑
            </button>
          </div>
        </div>
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 mt-3">
          ${liquidBar}
          <div>
            <div class="flex justify-between text-xs font-semibold mb-1">
              <span class="text-slate-500">⛽ Carburant</span>
              <span class="text-slate-700">${Math.round(v.fuelQuantity)} / ${fuelMax}L</span>
            </div>
            <div class="h-1.5 bg-slate-200 rounded-full overflow-hidden">
              <div class="progress-bar h-full ${fuelColor} rounded-full" style="width: ${fuelPct}%"></div>
            </div>
          </div>
        </div>
      </div>
    `;
  }).join('');
}

// ── Édition véhicule ─────────────────────────────────────────
let editingVehicleId = null;

function openEditModal(id) {
  const v = vehiclesCache.find(x => x.id === id);
  if (!v) return;
  editingVehicleId = id;
  document.getElementById('edit-vehicle-id').textContent = `#${id}`;
  document.getElementById('edit-liquidType').value = v.liquidType;
  document.getElementById('edit-crewMember').value = v.crewMember;
  document.getElementById('edit-status').textContent = '';
  document.getElementById('edit-modal').classList.remove('hidden');
}

function closeEditModal() {
  editingVehicleId = null;
  document.getElementById('edit-modal').classList.add('hidden');
}

async function submitEditVehicle() {
  if (editingVehicleId == null) return;
  const v = vehiclesCache.find(x => x.id === editingVehicleId);
  if (!v) return;

  // On renvoie le DTO complet (id, coords, quantités…) — le simulateur refuse en 500 sinon
  const data = {
    id: v.id,
    lon: v.lon,
    lat: v.lat,
    type: v.type,
    liquidType: document.getElementById('edit-liquidType').value,
    liquidQuantity: v.liquidQuantity,
    fuelQuantity: v.fuelQuantity,
    crewMember: Number(document.getElementById('edit-crewMember').value),
    facilityRefID: v.facilityRefID,
  };

  try {
    await updateVehicle(editingVehicleId, data);
    document.getElementById('edit-status').textContent = '✅ Modifié';
    closeEditModal();
    loadVehicles();
  } catch (err) {
    document.getElementById('edit-status').textContent = '❌ Erreur';
    console.error(err);
  }
}

async function toggleRecallOne(id, isCurrentlyRecalled) {
  try {
    if (isCurrentlyRecalled) {
      // Annule le rappel → véhicule peut reprendre les missions
      await cancelRecallOneVehicle(id);
    } else {
      // Active le rappel individuel → ramène à SA caserne d'attache
      const res = await recallOneVehicle(id);
      if (!res.data.inMission) {
        const v = vehiclesCache.find(x => x.id === id);
        const f = v && facilitiesCache.find(x => x.id === v.facilityRefID);
        if (f) await moveVehicle(id, f.lat, f.lon);
      }
    }
    loadVehicles();
  } catch (err) {
    alert(`❌ Erreur sur #${id}`);
    console.error(err);
  }
}

async function removeVehicle(id) {
  if (!confirm(`Supprimer le véhicule #${id} ?\n\n⚠️ Si le véhicule n'est pas à la caserne : pénalité -500 points !`)) {
    return;
  }

  try {
    const res = await deleteVehicle(id);
    if (res.data === false) {
      alert(`❌ Suppression refusée pour #${id} (véhicule en mission)`);
    } else {
      loadVehicles();
    }
  } catch (err) {
    alert(`❌ Erreur lors de la suppression de #${id}`);
    console.error(err);
  }
}

async function submitCreateVehicle() {
  const data = {
    type: document.getElementById('type').value,
    liquidType: document.getElementById('liquidType').value,
    crewMember: Number(document.getElementById('crewMember').value),
    facilityRefID: Number(document.getElementById('facilityRefID').value)
  };

  try {
    await createVehicle(data);
    document.getElementById('create-status').textContent = '✅ Véhicule créé !';
    loadVehicles();
  } catch (err) {
    document.getElementById('create-status').textContent = '❌ Erreur';
    console.error(err);
  }
}

async function recallAllVehicles() {
  const status = document.getElementById('recall-status');
  const btn = document.getElementById('recall-btn');

  try {
    // Vérifie l'état courant
    const stateRes = await getRecallMode();
    const isRecallActive = stateRes.data.recallMode;

    if (isRecallActive) {
      // Désactive → reprend le dispatch normal
      await resumeDispatch();
      status.textContent = '▶️ Dispatch repris';
      if (btn) btn.textContent = '🏠 Rappeler à la caserne';
    } else {
      // Active → tous les véhicules en mission rentrent, plus de nouveau dispatch
      await recallAllVehiclesApi();
      status.textContent = '🏠 Mode rappel activé — les véhicules rentrent à la caserne';
      if (btn) btn.textContent = '▶️ Reprendre le dispatch';
    }
    loadVehicles();
  } catch (err) {
    status.textContent = '❌ Erreur';
    console.error(err);
  }
}

const REFRESH_INTERVAL_MS = 3000;

// Synchronise les hints (place + équipage) et l'input équipage avec le type sélectionné
function syncCrewMaxFromType() {
  const typeSel = document.getElementById('type');
  const input = document.getElementById('crewMember');
  const crewHint = document.getElementById('crew-max-hint');
  const spaceHint = document.getElementById('space-hint');
  if (!typeSel || !input) return;
  const max = CREW_CAPACITY_BY_TYPE[typeSel.value] ?? 0;
  const space = VEHICLE_SPACE_BY_TYPE[typeSel.value] ?? 0;
  input.max = max;
  input.value = max;
  if (crewHint) crewHint.textContent = `max : ${max}`;
  if (spaceHint) spaceHint.textContent = `${space} places`;
}

(async () => {
  await loadFacilities();
  await loadVehicles();
  const typeSel = document.getElementById('type');
  if (typeSel) {
    typeSel.addEventListener('change', syncCrewMaxFromType);
    syncCrewMaxFromType(); // initial
  }
  setInterval(() => {
    // Pas de refresh pendant l'édition pour ne pas écraser les inputs
    if (editingVehicleId != null) return;
    loadVehicles().catch(err => console.error('Refresh véhicules:', err));
  }, REFRESH_INTERVAL_MS);
})();
