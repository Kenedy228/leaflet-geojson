const selectionPoints = document.querySelector(".points-distance");

const selectionPointsControl = document.querySelector(
  ".points-distance-control"
);

const selectionPointsResetControl = document.querySelector(
  ".points-distance-reset-control"
);

const selectionPointsBlock = document.querySelector(".points-distance-info");

const selectedPointsList = document.querySelector(
  ".points-distance-selected-list"
);

const calculateDistanceControl = document.querySelector(
  ".points-distance-calculate-control"
);

let inSelectionMode = false;

let selectedMarkers = [];
const distanceLayerGroup = L.layerGroup().addTo(map);

selectionPointsControl.addEventListener("click", (e) => {
  if (inSelectionMode) {
    inSelectionMode = false;
    distanceLayerGroup.clearLayers();
    selectionPointsBlock.classList.add("display-none");
    selectionPointsResetControl.classList.add("display-none");

    selectedMarkers.forEach((point) =>
      point.layer.setStyle({ fillColor: "#ff7800" })
    );
  } else {
    inSelectionMode = true;
    selectionPointsBlock.classList.remove("display-none");
    selectionPointsResetControl.classList.remove("display-none");
  }

  renderSelectedPointsList();
  selectedMarkers = [];
});

function renderSelectedPointsList() {
  selectedPointsList.innerHTML = "";

  for (let i = 0; i < selectedMarkers.length; i++) {
    const li = document.createElement("li");
    li.textContent = `${selectedMarkers[i].name}`;
    selectedPointsList.appendChild(li);
  }
}

selectionPointsResetControl.addEventListener("click", (e) => {
  distanceLayerGroup.clearLayers();

  selectedMarkers.forEach((point) =>
    point.layer.setStyle({ fillColor: "#ff7800" })
  );

  selectedMarkers = [];
  renderSelectedPointsList();
});

calculateDistanceControl.addEventListener("click", (e) => {
  if (selectedMarkers.length < 2) {
    alert("Выберите хотя бы 2 точки");
    return;
  }

  const selected = selectedMarkers.map(({ name, lng, lat }) => ({ name, lng, lat }));
  const coords = selected.map((marker) => {
    return [marker.lng, marker.lat];
  });

  fetch(serverURL + "/distances", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ points: coords }),
  })
    .then((res) => res.json())
    .then((data) => {
      distanceLayerGroup.clearLayers();

      data.lines.forEach((lineData, index) => {
        const latlngs = [
          [lineData.from[1], lineData.from[0]],
          [lineData.to[1], lineData.to[0]],
        ];
        const line = L.polyline(latlngs, { color: "red" }).addTo(map);
        const popup = document.createElement("div");
        popup.textContent = `Дистанция участка ${selected[index].name}-${selected[index + 1].name}: ${lineData.distance.toFixed(2)} м`;
        popup.append(document.createElement("br"), document.createTextNode(
          `Общая дистанция: ${data.totalDistance.toFixed(2)} м`));
        line.bindPopup(popup);
        distanceLayerGroup.addLayer(line);
      });
    });
});
