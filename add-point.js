const addPointControl = document.querySelector(".add-point-control");
const addPointControlPanel = document.querySelector(".add-point-control-panel");
const addPointPointsContainer = document.querySelector(
  ".add-point-points-container"
);

const saveNewPointsControl = document.querySelector(".add-point-save");

let addPointMode = false;
let newMarkers = [];

addPointControl.addEventListener("click", (e) => {
  if (addPointMode) {
    addPointMode = false;
    addPointControlPanel.classList.add("display-none");
  } else {
    addPointMode = true;
    addPointControlPanel.classList.remove("display-none");
  }

  newMarkers = [];
});

map.on("click", function (e) {
  if (!addPointMode) return;

  const lat = e.latlng.lat;
  const lng = e.latlng.lng;

  const layer = L.circleMarker([lat, lng], {
    radius: 8,
    fillColor: "#00ff00",
    color: "#000",
    weight: 1,
    opacity: 1,
    fillOpacity: 0.8,
  }).addTo(map);

  newMarkers.push({
    id: Math.floor(Math.random() * 1000),
    marker: layer,
    name: "Новая точка",
  });

  layer.bindPopup("Новая точка").openPopup();

  renderNewPointInList(newMarkers[newMarkers.length - 1]);
});

addPointPointsContainer.addEventListener("click", function (e) {
  const btn = e.target.closest("button[data-action]");
  if (!btn) return;

  const action = btn.dataset.action;
  const id = btn.dataset.id;

  const found = newMarkers.find((m) => m.id == id);

  if (!found) return;

  if (action === "delete") {
    found.marker.remove();
    newMarkers = newMarkers.filter((m) => m.id !== id);

    const row = btn.closest(".change-add-point-name");
    if (row) row.remove();
  }

  if (action === "save") {
    const row = btn.closest(".change-add-point-name");
    const input = row.querySelector("input");
    if (!input) return;

    const newName = input.value;

    found.name = newName;

    found.marker.bindPopup(newName);
  }
});

saveNewPointsControl.addEventListener("click", async (e) => {
  if (newMarkers.length === 0) {
    alert("Нет новых точек для отправки");
    return;
  }

  const payload = newMarkers.map((m) => ({
    name: m.name,
    lat: m.marker.getLatLng().lat,
    lng: m.marker.getLatLng().lng,
  }));

  const res = await fetch(serverURL + "/points/add", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  if (res.ok) {
    alert("Точки успешно добавлены на сервер");
    newMarkers.forEach((m) => m.marker.setStyle({ fillColor: "#0000ff" }));
    newMarkers = [];
  } else {
    alert("Ошибка при добавлении точек");
  }
});

function renderNewPointInList(marker) {
  const row = document.createElement("div");
  row.className = "change-add-point-name d-flex justify-content-between";

  const input = document.createElement("input");
  input.className = "my-2";
  input.type = "text";
  input.style.height = "38px";
  input.value = marker.name;

  const saveBtn = document.createElement("button");
  saveBtn.className = "btn btn-success my-2 save-new-point";
  saveBtn.dataset.action = "save";
  saveBtn.dataset.id = marker.id;
  saveBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" class="bi bi-check" viewBox="0 0 16 16">
    <path d="M10.97 4.97a.75.75 0 0 1 1.07 1.05l-3.99 4.99a.75.75 0 0 1-1.08.02L4.324 8.384a.75.75 0 1 1 1.06-1.06l2.094 2.093 3.473-4.425z"/>
  </svg>`;

  const deleteBtn = document.createElement("button");
  deleteBtn.className = "btn btn-danger my-2 drop-new-point";
  deleteBtn.dataset.action = "delete";
  deleteBtn.dataset.id = marker.id;
  deleteBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" class="bi bi-trash-fill" viewBox="0 0 16 16">
    <path d="M2.5 1a1 1 0 0 0-1 1v1a1 1 0 0 0 1 1H3v9a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2V4h.5a1 1 0 0 0 1-1V2a1 1 0 0 0-1-1H10a1 1 0 0 0-1-1H7a1 1 0 0 0-1 1zm3 4a.5.5 0 0 1 .5.5v7a.5.5 0 0 1-1 0v-7a.5.5 0 0 1 .5-.5M8 5a.5.5 0 0 1 .5.5v7a.5.5 0 0 1-1 0v-7A.5.5 0 0 1 8 5m3 .5v7a.5.5 0 0 1-1 0v-7a.5.5 0 0 1 1 0"/>
  </svg>`;

  row.appendChild(input);
  row.appendChild(saveBtn);
  row.appendChild(deleteBtn);

  addPointPointsContainer.appendChild(row);
}
