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