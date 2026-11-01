const roadsControl = document.querySelector(".roads-control");
const roadsMarkers = L.layerGroup().addTo(map);

roadsControl.addEventListener("click", () => {
  if (roadsControl.classList.contains("hidden")) {
    fetch(serverURL + "/roads")
      .then((res) => res.json())
      .then((data) => {
        roadsMarkers.clearLayers();

        data.forEach((road) => {
          const geom =
            typeof road.geojson === "string"
              ? JSON.parse(road.geojson)
              : road.geojson;

          const roadLayer = L.geoJSON(geom, {
            style: {
              color: "#ff0000ff",
              weight: 2,
              opacity: 0.8,
            },
            onEachFeature: function (feature, layer) {
              layer.bindPopup(`<b>${road.name || "Без названия"}</b>`);
            },
          });

          roadsMarkers.addLayer(roadLayer);
        });
      });

    showRoadsRelatedControls();
  } else if (roadsControl.classList.contains("visible")) {
    hideRoadsRelatedControls();
  }
});

function showRoadsRelatedControls() {
  roadsControl.classList.remove("hidden");
  roadsControl.classList.add("visible");
}

function hideRoadsRelatedControls() {
  roadsMarkers.clearLayers();
  roadsControl.classList.remove("visible");
  roadsControl.classList.add("hidden");
}
