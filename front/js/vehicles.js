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
    await axios.post(`${API}/vehicle/${TEAM_UUID}`, data);
    document.getElementById('create-status').textContent = '✅ Véhicule créé !';
    loadVehicles();
  } catch (err) {
    document.getElementById('create-status').textContent = '❌ Erreur';
    console.error(err);
  }
}

loadVehicles();
