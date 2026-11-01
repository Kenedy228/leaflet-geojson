const addPointControl = document.querySelector(".add-point-control");
const addPointControlPanel = document.querySelector(".add-point-control-panel");
const addPointPointsContainer = document.querySelector(
  ".add-point-points-container",
);

const saveNewPointsControl = document.querySelector(".add-point-save");
const pointSaveStatus = document.querySelector(".point-save-status");
const unconfirmedSaveMessage =
  "Сервер не подтвердил созданные точки. Черновики сохранены; проверьте сохранённые точки перед повторной отправкой.";

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

    setDraftName(found, input.value);
  }
});

saveNewPointsControl.addEventListener("click", async (e) => {
  if (saveNewPointsControl.disabled) return;
  if (newMarkers.length === 0) {
    pointSaveStatus.textContent = "Нет новых точек для отправки";
    return;
  }

  const pending = [...newMarkers];
  // The batch uses the visible input, even if its preview check button was not pressed.
  pending.forEach((draft) =>
    setDraftName(draft, draft.row.querySelector("input").value),
  );
  const payload = pending.map((m) => ({
    name: m.name,
    lat: m.marker.getLatLng().lat,
    lng: m.marker.getLatLng().lng,
  }));

  saveNewPointsControl.disabled = true;
  pointSaveStatus.textContent = "Сохранение точек…";
  pendingPointWrites.create = true;
  pending.forEach((draft) => setDraftDisabled(draft, true));
  try {
    const res = await pointWrite("/points/add", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      const fallback =
        res.status === 400
          ? "Пакет отклонён. Проверьте: 1–1000 точек, непустые названия до 255 символов и допустимые координаты. Черновики сохранены."
          : res.status >= 500
            ? "Ошибка сервера при сохранении пакета. Черновики сохранены; повторите попытку."
            : `Не удалось сохранить пакет (HTTP ${res.status}). Черновики сохранены.`;
      throw new Error(await apiErrorMessage(res, fallback));
    }
    let result;
    try {
      result = await res.json();
    } catch {
      throw new Error(unconfirmedSaveMessage);
    }
    if (
      !result ||
      result.inserted !== pending.length ||
      !Array.isArray(result.points) ||
      result.points.length !== pending.length ||
      result.points.some(
        (point) =>
          !point ||
          point.source !== "user" ||
          typeof point.id !== "string" ||
          !/^user:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
            point.id,
          ),
      ) ||
      new Set(result.points.map((point) => point.id)).size !== pending.length
    ) {
      throw new Error(unconfirmedSaveMessage);
    }
    const savedIds = new Set(pending.map((draft) => draft.id));
    pending.forEach((draft) => {
      draft.marker.remove();
      draft.row.remove();
    });
    newMarkers = newMarkers.filter((draft) => !savedIds.has(draft.id));
    if (pointsControl.checked) result.points.forEach(addPointToLayer);
    pointSaveStatus.textContent = `Сохранено точек: ${result.inserted}.`;
  } catch (error) {
    pointSaveStatus.textContent =
      error instanceof TypeError
        ? "Не удалось получить подтверждение от сервера. Черновики сохранены; проверьте сохранённые точки перед повторной отправкой."
        : error.message || unconfirmedSaveMessage;
  } finally {
    pendingPointWrites.create = false;
    pending.forEach((draft) => setDraftDisabled(draft, false));
    saveNewPointsControl.disabled = !accessState.can_edit;
  }
});

function setDraftName(draft, name) {
  draft.name = name;
  const popup = document.createElement("span");
  popup.textContent = name;
  draft.marker.bindPopup(popup);
}

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
