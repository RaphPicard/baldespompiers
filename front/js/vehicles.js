async function loadVehicles() {
  const res = await getVehicles();
  const vehicles = res.data;
  console.log(res.data);
  const list = document.getElementById('vehicle-list');

  if (vehicles.length === 0) {
    list.innerHTML = '<p class="text-gray-400">Aucun véhicule</p>';
    return;
  }

  list.innerHTML = vehicles.map(v => `
    <div class="border rounded-lg p-3 flex justify-between items-center">
      <div>
        <p class="font-semibold">🚒 #${v.id} — ${v.type}</p>
        <p class="text-sm text-gray-500">Liquide : ${v.liquidType} (${v.liquidQuantity}L)</p>
        <p class="text-sm text-gray-500">Carburant : ${v.fuel}L — Équipage : ${v.crewMember}</p>
      </div>
      <div class="flex gap-2">
        <button onclick="recallOne(${v.id})" class="bg-blue-500 hover:bg-blue-600 text-white font-semibold rounded px-3 py-1 text-sm">
          🏠 Caserne
        </button>
        <button onclick="removeVehicle(${v.id})" class="bg-red-500 hover:bg-red-600 text-white font-semibold rounded px-3 py-1 text-sm">
          🗑 Supprimer
        </button>
      </div>
    </div>
  `).join('');
}

async function recallOne(id) {
  const FACILITY_LAT = 45.73158119172101;
  const FACILITY_LON = 4.890602482113532;
  try {
    const res = await recallOneVehicle(id);
    if (!res.data.inMission) {
      // Véhicule libre → on déclenche aussi un déplacement direct vers la caserne
      await moveVehicle(id, FACILITY_LAT, FACILITY_LON);
    }
    loadVehicles();
  } catch (err) {
    alert(`❌ Erreur rappel #${id}`);
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
    liquidQuantity: Number(document.getElementById('liquidQuantity').value),
    fuel: Number(document.getElementById('fuel').value),
    crewMember: Number(document.getElementById('crewMember').value),
    facilityRefID: FACILITY_ID
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

loadVehicles();
