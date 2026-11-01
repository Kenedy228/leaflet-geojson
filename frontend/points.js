const pointsControl = document.querySelector(".points-control");
const pointsMarkers = L.layerGroup().addTo(map);

pointsControl.addEventListener("click", () => {
  if (pointsControl.classList.contains("hidden")) {
    fetch(serverURL + "/points")
      .then((res) => res.json())
      .then((data) => {
        pointsMarkers.clearLayers();
        data.forEach((item) => {
          const geom =
            typeof item.geojson === "string"
              ? JSON.parse(item.geojson)
              : item.geojson;

          const marker = L.geoJSON(geom, {
            pointToLayer: function (feature, latlng) {
              return L.circleMarker(latlng, {
                radius: 8,
                fillColor: "#ff7800",
                color: "#000",
                weight: 1,
                opacity: 1,
                fillOpacity: 0.8,
              });
            },
            onEachFeature: function (feature, layer) {
              layer.bindPopup(item.name || "Нет названия");

              layer.on("click", function (e) {
                if (!inSelectionMode) {
                  deletePointControl.classList.remove("display-none");
                  pointToDelete = {
                    id: item.osm_id,
                  };
                }

                const latlng = layer.getLatLng();
                const markerData = {
                  name: item.name,
                  lat: latlng.lat,
                  lng: latlng.lng,
                  layer: layer,
                };

                const found = selectedMarkers.find(
                  (m) =>
                    m.name === markerData.name &&
                    m.lat === markerData.lat &&
                    m.lng === markerData.lng
                );

                if (found) {
                  layer.setStyle({ fillColor: "#ff7800" });
                  selectedMarkers = selectedMarkers.filter(
                    (m) =>
                      !(
                        m.name === markerData.name &&
                        m.lat === markerData.lat &&
                        m.lng === markerData.lng
                      )
                  );
                } else {
                  selectedMarkers.push(markerData);
                  layer.setStyle({ fillColor: "#000000" });
                  layer.openPopup();
                }

                renderSelectedPointsList();
              });
            },
          });

          pointsMarkers.addLayer(marker);
        });
      });

    showPointsRelatedControls();
  } else if (pointsControl.classList.contains("visible")) {
    hidePointsRelatedControls();
  }
});

function showPointsRelatedControls() {
  pointsControl.classList.remove("hidden");
  pointsControl.classList.add("visible");
  selectionPoints.classList.remove("display-none");
  deletePointControl.classList.add("display-none");
}

function hidePointsRelatedControls() {
  pointsMarkers.clearLayers();
  pointsControl.classList.remove("visible");
  pointsControl.classList.add("hidden");
  selectionPoints.classList.add("display-none");
  deletePointControl.classList.add("display-none");
}
