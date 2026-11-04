import { Api } from "./lib/api";
import "./theme.css";
import "./login.css";

function element<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error("Login element is missing");
  return node as T;
}
const form = document.querySelector("form")!;
const submit = element<HTMLButtonElement>("login-submit");
const retry = element<HTMLButtonElement>("session-retry");
const status = element("session-status");
const csrf = element<HTMLInputElement>("csrf");
const error = element("login-error");
const controller = new AbortController();
let ready = false;
error.hidden = !new URLSearchParams(location.search).has("error");

async function prepare() {
  ready = false;
  submit.disabled = true;
  retry.hidden = true;
  status.textContent = "Подготовка формы входа…";
  try {
    const session = await new Api().session(controller.signal);
    csrf.value = session.csrf_token;
    ready = session.editing_enabled;
    submit.disabled = !ready;
    status.textContent = ready
      ? ""
      : "Редактирование отключено. Вы можете продолжить просмотр карты.";
  } catch {
    if (controller.signal.aborted) return;
    status.textContent =
      "Не удалось подготовить вход. Проверьте соединение и повторите.";
    retry.hidden = false;
  }
}
retry.addEventListener("click", () => void prepare());
form.addEventListener("submit", (event) => {
  if (!ready) {
    event.preventDefault();
    return;
  }
  ready = false;
  submit.disabled = true;
  status.textContent = "Выполняется вход…";
});
window.addEventListener("pagehide", () => controller.abort(), { once: true });
window.addEventListener("pageshow", (event) => {
  if (event.persisted) location.reload();
});
void prepare();
