const pointsControl = document.querySelector(".points-control");
const pointsMarkers = L.layerGroup().addTo(map);

pointsControl.addEventListener("click", () => {
  if (pointsControl.classList.contains("hidden")) {
    fetch(serverURL + "/points")
      .then(async (res) => {
        if (!res.ok) throw new Error(await apiErrorMessage(res, "Не удалось загрузить точки"));
        return res.json();
      })
      .then((data) => {
        pointsMarkers.clearLayers();
        data.forEach(addPointToLayer);
      })
      .catch((error) => alert(error.message));

    showPointsRelatedControls();
  } else if (pointsControl.classList.contains("visible")) {
    hidePointsRelatedControls();
  }
});

function addPointToLayer(item) {
  const geom = typeof item.geojson === "string" ? JSON.parse(item.geojson) : item.geojson;
  const marker = L.geoJSON(geom, {
    pointToLayer(feature, latlng) {
      return L.circleMarker(latlng, {
        radius: 8,
        fillColor: "#ff7800",
        color: "#000",
        weight: 1,
        opacity: 1,
        fillOpacity: 0.8,
        bubblingMouseEvents: false,
      });
    },
    onEachFeature(feature, layer) {
      const popup = document.createElement("span");
      popup.textContent = item.name || "Нет названия";
      layer.bindPopup(popup);

      layer.on("click", () => {
        if (!inSelectionMode) {
          const canDelete = item.source === "user" && accessState.can_edit;
          deletePointControl.classList.toggle("display-none", !canDelete);
          pointToDelete = canDelete ? { id: item.id } : undefined;
          return;
        }

        const latlng = layer.getLatLng();
        const markerData = {
          id: item.id,
          name: item.name,
          lat: latlng.lat,
          lng: latlng.lng,
          layer,
        };
        const found = selectedMarkers.some((point) => point.id === item.id);
        if (found) {
          layer.setStyle({ fillColor: "#ff7800" });
          selectedMarkers = selectedMarkers.filter((point) => point.id !== item.id);
        } else {
          selectedMarkers.push(markerData);
          layer.setStyle({ fillColor: "#000000" });
          layer.openPopup();
        }
        renderSelectedPointsList();
      });
    },
  });
  marker.pointId = item.id;
  pointsMarkers.addLayer(marker);
}

function showPointsRelatedControls() {
  pointsControl.classList.remove("hidden");
  pointsControl.classList.add("visible");
  selectionPoints.classList.remove("display-none");
  deletePointControl.classList.add("display-none");
  pointToDelete = undefined;
}

function hidePointsRelatedControls() {
  pointsMarkers.clearLayers();
  pointsControl.classList.remove("visible");
  pointsControl.classList.add("hidden");
  selectionPoints.classList.add("display-none");
  deletePointControl.classList.add("display-none");
  pointToDelete = undefined;
}
