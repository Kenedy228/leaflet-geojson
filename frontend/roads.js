const roadsControl = document.querySelector(".roads-control");
const roadsMarkers = L.layerGroup().addTo(map);

roadsControl.addEventListener("click", () => {
  if (roadsControl.classList.contains("hidden")) {
    fetch(serverURL + "/roads")
      .then(async (res) => {
        if (!res.ok) throw new Error(await apiErrorMessage(res, "Не удалось загрузить дороги"));
        return res.json();
      })
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
              const popup = document.createElement("b");
              popup.textContent = road.name || "Без названия";
              layer.bindPopup(popup);
            },
          });

          roadsMarkers.addLayer(roadLayer);
        });
      })
      .catch((error) => alert(error.message || "Не удалось загрузить дороги"));

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
