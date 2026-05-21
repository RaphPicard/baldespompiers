const FACILITY_TOLERANCE = 0.0005; // ~50m

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

async function loadVehicles() {
  const [vRes, rRes] = await Promise.all([getVehicles(), getRecallMode()]);
  const vehicles = vRes.data;
  vehiclesCache = vehicles;
  const globalRecall = rRes.data.recallMode;
  const recalledIds = new Set(rRes.data.recalledIds || []);
  const list = document.getElementById('vehicle-list');

  // Synchronise l'apparence du bouton global avec l'état réel
  const globalBtn = document.getElementById('recall-btn');
  if (globalBtn) globalBtn.textContent = globalRecall ? '▶️ Reprendre le dispatch' : '🏠 Rappeler à la caserne';

  if (vehicles.length === 0) {
    list.innerHTML = '<p class="text-gray-400">Aucun véhicule</p>';
    return;
  }

  list.innerHTML = vehicles.map(v => {
    const isRecalled = globalRecall || recalledIds.has(v.id);
    const recallClass = isRecalled ? 'bg-green-500 hover:bg-green-600' : 'bg-blue-500 hover:bg-blue-600';
    const recallLabel = isRecalled ? '▶️ Reprendre mission' : '🏠 Caserne';
    const atFacility = isAtFacility(v);
    const statusBadge = atFacility
      ? '<span class="inline-block bg-green-100 text-green-700 text-xs font-semibold rounded px-2 py-0.5 ml-2">🏠 À la caserne</span>'
      : '<span class="inline-block bg-orange-100 text-orange-700 text-xs font-semibold rounded px-2 py-0.5 ml-2">🚗 En mission</span>';
    const editBtn = atFacility
      ? `<button onclick="openEditModal(${v.id})" class="bg-gray-500 hover:bg-gray-600 text-white font-semibold rounded px-3 py-1 text-sm">✏️ Modifier</button>`
      : '';
    return `
      <div class="border rounded-lg p-3 flex justify-between items-center">
        <div>
          <p class="font-semibold">🚒 #${v.id} — ${v.type}${statusBadge}</p>
          <p class="text-sm text-gray-500">Caserne : ${facilityLabelOf(v)}</p>
          <p class="text-sm text-gray-500">Liquide : ${v.liquidType} (${v.liquidQuantity}L)</p>
          <p class="text-sm text-gray-500">Carburant : ${v.fuel}L — Équipage : ${v.crewMember}</p>
        </div>
        <div class="flex gap-2 flex-wrap justify-end">
          ${editBtn}
          <button onclick="toggleRecallOne(${v.id}, ${isRecalled})" class="${recallClass} text-white font-semibold rounded px-3 py-1 text-sm">
            ${recallLabel}
          </button>
          <button onclick="removeVehicle(${v.id})" class="bg-red-500 hover:bg-red-600 text-white font-semibold rounded px-3 py-1 text-sm">
            🗑 Supprimer
          </button>
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

  // On renvoie le DTO complet (type, facilityRefID, etc.) pour ne pas casser le simulateur
  const data = {
    type: v.type,
    liquidType: document.getElementById('edit-liquidType').value,
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

(async () => {
  await loadFacilities();
  await loadVehicles();
})();
