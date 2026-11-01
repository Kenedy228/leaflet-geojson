const polygonsControl = document.querySelector(".polygons-control");
const polygonsMarkers = L.layerGroup().addTo(map);

polygonsControl.addEventListener("click", () => {
  if (polygonsControl.classList.contains("hidden")) {
    fetch(serverURL + "/polygons")
      .then(async (res) => {
        if (!res.ok)
          throw new Error(
            await apiErrorMessage(res, "Не удалось загрузить полигоны"),
          );
        return res.json();
      })
      .then((data) => {
        polygonsMarkers.clearLayers();

        data.forEach((poly) => {
          const geom =
            typeof poly.geojson === "string"
              ? JSON.parse(poly.geojson)
              : poly.geojson;

          const polygonLayer = L.geoJSON(geom, {
            style: {
              color: "#008800", // граница
              fillColor: "#00ff00", // заливка
              weight: 2,
              opacity: 0.8,
              fillOpacity: 0.4,
            },
            onEachFeature: function (feature, layer) {
              const popup = document.createElement("b");
              popup.textContent = poly.name || "Без названия";
              layer.bindPopup(popup);
            },
          });

          polygonsMarkers.addLayer(polygonLayer);
        });
      })
      .catch((error) =>
        alert(error.message || "Не удалось загрузить полигоны"),
      );

    showPolygonsRelatedControls();
  } else if (polygonsControl.classList.contains("visible")) {
    hidePolygonsRelatedControls();
  }
});

function showPolygonsRelatedControls() {
  polygonsControl.classList.remove("hidden");
  polygonsControl.classList.add("visible");
}

function hidePolygonsRelatedControls() {
  polygonsMarkers.clearLayers();
  polygonsControl.classList.remove("visible");
  polygonsControl.classList.add("hidden");
}
