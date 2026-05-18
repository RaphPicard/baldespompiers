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
    </div>
  `).join('');
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
  const FACILITY_LAT = 45.73158119172101;
  const FACILITY_LON = 4.890602482113532;
  const status = document.getElementById('recall-status');

  const res = await getVehicles();
  const vehicles = res.data;

  if (vehicles.length === 0) {
    status.textContent = 'Aucun véhicule à rappeler';
    return;
  }

  let success = 0;
  let failed = 0;
  const errors = [];

  for (const v of vehicles) {
    status.textContent = `⏳ Rappel ${success + failed + 1}/${vehicles.length}...`;
    try {
      await moveVehicle(v.id, FACILITY_LAT, FACILITY_LON);
      success++;
    } catch (err) {
      failed++;
      const code = err.response?.status || '?';
      errors.push(`#${v.id} (${code})`);
      console.error(`Erreur véhicule #${v.id}:`, err);
    }
    await new Promise(r => setTimeout(r, 100));
  }

  let msg = `✅ ${success}/${vehicles.length} ramené(s)`;
  if (failed > 0) msg += ` — ❌ Échecs: ${errors.join(', ')}`;
  status.textContent = msg;
  loadVehicles();
}

loadVehicles();
