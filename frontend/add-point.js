const addPointControl = document.querySelector(".add-point-control");
const addPointControlPanel = document.querySelector(".add-point-control-panel");
const addPointPointsContainer = document.querySelector(
  ".add-point-points-container"
);

const saveNewPointsControl = document.querySelector(".add-point-save");

let addPointMode = false;
let newMarkers = [];
let nextDraftId = 0n;

addPointControl.addEventListener("click", (e) => {
  if (!accessState.can_edit) return;
  if (addPointMode) {
    addPointMode = false;
    addPointControlPanel.classList.add("display-none");
  } else {
    addPointMode = true;
    addPointControlPanel.classList.remove("display-none");
  }
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
    id: `draft:${++nextDraftId}`,
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

  const found = newMarkers.find((m) => m.id === id);

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

    const popup = document.createElement("span");
    popup.textContent = newName;
    found.marker.bindPopup(popup);
  }
});

saveNewPointsControl.addEventListener("click", async (e) => {
  if (saveNewPointsControl.disabled) return;
  if (newMarkers.length === 0) {
    alert("Нет новых точек для отправки");
    return;
  }

  const pending = [...newMarkers];
  const payload = pending.map((m) => ({
    name: m.name,
    lat: m.marker.getLatLng().lat,
    lng: m.marker.getLatLng().lng,
  }));

  saveNewPointsControl.disabled = true;
  pendingPointWrites.create = true;
  pending.forEach((draft) => setDraftDisabled(draft, true));
  try {
    const res = await pointWrite("/points/add", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (!res.ok) throw new Error("Не удалось сохранить точки");
    const result = await res.json();
    if (result.inserted !== pending.length || !Array.isArray(result.points) ||
        result.points.length !== pending.length ||
        result.points.some((point) => point.source !== "user" ||
          typeof point.id !== "string" || !point.id.startsWith("user:"))) {
      throw new Error("Сервер не подтвердил идентификаторы созданных точек");
    }
    const savedIds = new Set(pending.map((draft) => draft.id));
    pending.forEach((draft) => {
      draft.marker.remove();
      draft.row.remove();
    });
    newMarkers = newMarkers.filter((draft) => !savedIds.has(draft.id));
    if (pointsControl.checked) result.points.forEach(addPointToLayer);
    alert("Точки успешно добавлены на сервер");
  } catch (error) {
    alert(error.message || "Ошибка при добавлении точек");
  } finally {
    pendingPointWrites.create = false;
    pending.forEach((draft) => setDraftDisabled(draft, false));
    saveNewPointsControl.disabled = !accessState.can_edit;
  }
});

function setDraftDisabled(draft, disabled) {
  draft.row.querySelectorAll("input, button").forEach((control) => {
    control.disabled = disabled;
  });
}

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

  marker.row = row;
  addPointPointsContainer.appendChild(row);
}
