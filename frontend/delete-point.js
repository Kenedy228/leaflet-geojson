const deletePointControl = document.querySelector(".delete-points-control");

let pointToDelete = undefined;

deletePointControl.addEventListener("click", async () => {
  if (!pointToDelete || deletePointControl.disabled) return;
  const id = pointToDelete.id;
  deletePointControl.disabled = true;
  pendingPointWrites.delete = true;
  try {
    const response = await pointWrite(`/points/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
    if (!response.ok) throw new Error("Не удалось удалить точку");
    pointsMarkers.getLayers().forEach((layer) => {
      if (layer.pointId === id) pointsMarkers.removeLayer(layer);
    });
    selectedMarkers = selectedMarkers.filter((point) => point.id !== id);
    distanceLayerGroup.clearLayers();
    renderSelectedPointsList();
    // A different point may have been selected while this request was pending.
    if (pointToDelete?.id === id) {
      pointToDelete = undefined;
      deletePointControl.classList.add("display-none");
    }
  } catch (error) {
    alert(error.message || "Ошибка при удалении точки");
  } finally {
    pendingPointWrites.delete = false;
    deletePointControl.disabled = !accessState.can_edit;
  }
});
