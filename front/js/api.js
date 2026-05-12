const getFires = () => axios.get(`${API}/fires`);
const getVehicles = () => axios.get(`${API}/vehiclebyteam/${TEAM_UUID}`);
const getFacility = () => axios.get(`${API}/facility/${FACILITY_ID}`);
const moveVehicle = (id, lat, lon) => axios.put(`${API}/vehicle/move/${TEAM_UUID}/${id}`, { lat, lon });
const createVehicle = (data) => axios.post(`${API}/vehicle/${TEAM_UUID}`, data);
