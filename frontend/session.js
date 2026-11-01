let accessState = { editing_enabled: false, can_edit: false };
const pendingPointWrites = { create: false, delete: false };

function renderAccess(message) {
  document.querySelector(".access-status").textContent =
    message ||
    (accessState.can_edit
      ? "Вы вошли как редактор общих точек."
      : accessState.editing_enabled
        ? "Просмотр. Для изменения точек войдите как редактор."
        : "Режим просмотра: запись отключена на сервере.");
  document.querySelector(".access-login").hidden =
    !accessState.editing_enabled || accessState.can_edit;
  document.querySelector(".access-logout").hidden = !accessState.can_edit;
  document.querySelector(".add-point-control input").disabled =
    !accessState.can_edit;
  document.querySelector(".add-point-save").disabled =
    !accessState.can_edit || pendingPointWrites.create;
  document.querySelector(".delete-points-control").disabled =
    !accessState.can_edit || pendingPointWrites.delete;
  if (!accessState.can_edit) {
    document.querySelector(".add-point-control input").checked = false;
    if (typeof addPointMode !== "undefined") addPointMode = false;
  }
}

async function refreshAccess() {
  try {
    const response = await fetch("/api/v1/session", {
      cache: "no-store",
      credentials: "same-origin",
    });
    if (!response.ok) throw new Error();
    const session = await response.json();
    if (
      typeof session.can_edit !== "boolean" ||
      typeof session.editing_enabled !== "boolean"
    ) {
      throw new Error();
    }
    accessState = session;
    renderAccess();
    return session;
  } catch {
    accessState = { editing_enabled: false, can_edit: false };
    renderAccess(
      "Не удалось проверить права. Редактирование недоступно; обновите страницу.",
    );
    throw new Error("Не удалось проверить права редактирования");
  }
}

async function pointWrite(path, options) {
  // Refresh before each write: sessions/CSRF tokens can change in another tab.
  const session = await refreshAccess();
  if (!session.can_edit)
    throw new Error("Редактирование недоступно. Войдите как редактор.");
  const response = await fetch(serverURL + path, {
    ...options,
    credentials: "same-origin",
    headers: { ...options.headers, [session.csrf_header]: session.csrf_token },
  });
  if (response.status === 401 || response.status === 403) {
    const message = await apiErrorMessage(
      response,
      "Сеанс или права изменились. Проверьте вход.",
    );
    await refreshAccess();
    throw new Error(message + " Несохранённые точки остаются в черновиках.");
  }
  return response;
}

document.addEventListener("DOMContentLoaded", () => {
  refreshAccess().catch(() => {});
  document
    .querySelector(".access-logout")
    .addEventListener("click", async () => {
      const button = document.querySelector(".access-logout");
      button.disabled = true;
      try {
        const session = await refreshAccess();
        const response = await fetch("/logout", {
          method: "POST",
          credentials: "same-origin",
          headers: { [session.csrf_header]: session.csrf_token },
        });
        if (!response.ok)
          throw new Error("Не удалось выйти. Обновите страницу и повторите.");
        // Keep local drafts visible; server permission, rather than reloading, disables saves.
        await refreshAccess();
      } catch (error) {
        document.querySelector(".access-status").textContent = error.message;
      } finally {
        button.disabled = false;
      }
    });
});
