const map = L.map('map').setView([45.75, 4.85], 13);

L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png').addTo(map);

const fireIcon = L.divIcon({
  html: `
    <div style="
      background: #e87020;
      border-radius: 50%;
      width: 36px;
      height: 36px;
      display: flex;
      align-items: center;
      justify-content: center;
    ">
      <img src="../images/fire.svg" style="width:20px;height:20px;filter:brightness(0) invert(1);"/>
    </div>
  `,
  iconSize: [36, 36],
  iconAnchor: [18, 18],
  className: ''
});

const vehicleIcon = L.divIcon({
  html: `
    <div style="
      background: white;
      border-radius: 50%;
      width: 36px;
      height: 36px;
      display: flex;
      align-items: center;
      justify-content: center;
      border: 2px solid #e87020;
    ">
      <img src="../images/vehicle.svg" style="width:20px;height:20px;"/>
    </div>
  `,
  iconSize: [36, 36],
  iconAnchor: [18, 18],
  className: ''
});

const facilityIcon = L.divIcon({
  html: `
    <div style="
      background: white;
      width: 40px;
      height: 40px;
      display: flex;
      align-items: center;
      justify-content: center;
      border: 3px solid #22c55e;
    ">
      <img src="../images/facility.svg" style="width:24px;height:24px;"/>
    </div>
  `,
  iconSize: [40, 40],
  iconAnchor: [20, 20],
  className: ''
});

let fireMarkers = [];
let vehicleMarkers = [];
let facilityMarker = null;

async function fetchFires() {
  try {
    const res = await getFires();
    fireMarkers.forEach(m => map.removeLayer(m));
    fireMarkers = [];
    res.data.forEach(fire => {
      const marker = L.marker([fire.lat, fire.lon], { icon: fireIcon })
        .bindPopup(`
          <b>🔥 Feu #${fire.id}</b><br/>
          Type : ${fire.type}<br/>
          Intensité : ${fire.intensity.toFixed(2)}<br/>
          Étendue : ${fire.range.toFixed(2)}
        `)
        .addTo(map);
      fireMarkers.push(marker);
    });
  } catch (err) {
    console.error(err);
  }
}

async function fetchVehicles() {
  try {
    const res = await getVehicles();
    vehicleMarkers.forEach(m => map.removeLayer(m));
    vehicleMarkers = [];
    res.data.forEach(vehicle => {
      const marker = L.marker([vehicle.lat, vehicle.lon], { icon: vehicleIcon })
        .bindPopup(`
          <b>🚒 Véhicule #${vehicle.id}</b><br/>
          Type : ${vehicle.type}<br/>
          Liquide : ${vehicle.liquidType} (${vehicle.liquidQuantity}L)<br/>
          Carburant : ${vehicle.fuel}L<br/>
          Équipage : ${vehicle.crewMember}
        `)
        .addTo(map);
      vehicleMarkers.push(marker);
    });
  } catch (err) {
    console.error(err);
  }
}

async function fetchFacility() {
  try {
    const res = await getFacility();
    const facility = res.data;
    if (facilityMarker) map.removeLayer(facilityMarker);
    facilityMarker = L.marker([facility.lat, facility.lon], { icon: facilityIcon })
      .bindPopup(`
        <b>🏠 Caserne #${facility.id}</b><br/>
        Nom : ${facility.name}<br/>
        Véhicules : ${facility.vehicleIdSet.length} / ${facility.maxVehicleSpace}<br/>
        Effectifs : ${facility.peopleIdSet.length} / ${facility.peopleCapacity}
      `)
      .addTo(map);
  } catch (err) {
    console.error(err);
  }
}

fetchFires();
fetchVehicles();
fetchFacility();

setInterval(() => {
  fetchFires();
  fetchVehicles();
  fetchFacility();
}, 3000);

// pour push
