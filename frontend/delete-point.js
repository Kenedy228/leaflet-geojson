const deletePointControl = document.querySelector(".delete-points-control");

let pointToDelete = undefined;

deletePointControl.addEventListener("click", (e) => {
  fetch(serverURL + `/points/${pointToDelete.id}`, {
    method: "DELETE",
  }).then((res) => {
    if (res.ok) {
      location.reload();
    }
  });
});
