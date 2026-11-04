<script lang="ts">
  import { onMount, tick } from "svelte";
  import { AppModel } from "./lib/model.svelte";
  import LeafletMap from "./lib/LeafletMap.svelte";
  import LayerControls from "./lib/LayerControls.svelte";
  import DraftEditor from "./lib/DraftEditor.svelte";
  import DistancePanel from "./lib/DistancePanel.svelte";
  const model = new AppModel();
  let loginLink = $state.raw<HTMLAnchorElement | undefined>(undefined);
  let logoutButton = $state.raw<HTMLButtonElement | undefined>(undefined);
  async function leaveEditor() {
    const trigger = logoutButton;
    await model.logout();
    await tick();
    if (
      document.activeElement === document.body ||
      document.activeElement === trigger
    ) {
      (model.logoutFailed ? logoutButton : loginLink)?.focus();
    }
  }
  onMount(() => {
    void model.refreshSession().catch(() => {});
    return () => model.destroy();
  });
</script>

<svelte:window onfocus={() => void model.recheckSession()} />
<svelte:document
  onvisibilitychange={() => {
    if (document.visibilityState === "visible") void model.recheckSession();
  }}
/>

<svelte:head
  ><title>Геоатлас — интерактивная карта</title><meta
    name="description"
    content="Учебная GIS-карта: точки, дороги, полигоны и геодезические расстояния."
  /></svelte:head
>
<a class="skip-link" href="#controls">Перейти к управлению картой</a>
<header class="app-header">
  <div class="brand">
    <span class="brand-mark" aria-hidden="true">◈</span>
    <div>
      <h1>Геоатлас</h1>
      <p>Точки. Территории. Расстояния.</p>
    </div>
  </div>
  <span class="project-label">Учебная GIS · OpenStreetMap</span>
</header>
<main>
  <aside id="controls" tabindex="-1" aria-label="Управление картой">
    <section class="access-panel" aria-label="Доступ редактора">
      <p class="eyebrow">Рабочее пространство</p>
      <p class="access-status" role="status">
        {model.accessPending
          ? "Завершение сеанса редактора…"
          : model.accessError ||
            (model.canEdit
              ? "Вы вошли как редактор общих точек."
              : model.access.editing_enabled
                ? "Просмотр. Для изменения точек войдите как редактор."
                : "Режим просмотра: запись отключена на сервере.")}
      </p>
      {#if model.accessError && !model.logoutFailed}<button
          type="button"
          disabled={model.accessPending}
          onclick={() => void model.recheckSession()}>Проверить доступ</button
        >{/if}
      {#if model.access.editing_enabled && !model.canEdit && !model.accessPending && !model.logoutFailed}<a
          class="access-login"
          bind:this={loginLink}
          href="/login">Войти как редактор</a
        >{/if}
      {#if model.canEdit || model.accessPending || model.logoutFailed}<button
          class="access-logout"
          bind:this={logoutButton}
          type="button"
          disabled={model.accessPending}
          onclick={() => void leaveEditor()}
          >{model.accessPending
            ? "Выход…"
            : model.logoutFailed
              ? "Повторить выход"
              : "Выйти"}</button
        >{/if}
    </section>
    <LayerControls {model} />
    <DistancePanel {model} />
    {#if model.canEdit}
      <DraftEditor {model} />
      <section aria-labelledby="delete-heading">
        <h2 id="delete-heading">Выбранная точка</h2>
        <p>
          {model.deleteTarget
            ? model.deleteTarget.name
            : "Нажмите на пользовательскую точку вне режима расчёта."}
        </p>
        <button
          class="delete-points-control danger"
          type="button"
          disabled={!model.deleteTarget ||
            !model.canEdit ||
            model.deletePending}
          onclick={() => void model.deleteSelected()}>Удалить точку</button
        >
        <p class="delete-status" role="status">{model.deleteStatus}</p>
      </section>
    {/if}
    <footer>Учебный проект · Данные © OpenStreetMap contributors</footer>
  </aside>
  <section class="map-panel" aria-label="Интерактивная карта">
    <div class="map-caption">
      <span class="status-dot" aria-hidden="true"></span><span
        >Исследуйте карту</span
      ><span class="map-coordinate">WGS84 / EPSG:4326</span>
    </div>
    <LeafletMap {model} />
    <p class="map-hint">
      {model.addMode
        ? "Нажмите на карту, чтобы добавить черновик."
        : model.distanceMode
          ? "Нажимайте на точки в порядке расчёта."
          : "Включите слои в панели, чтобы увидеть геоданные."}
    </p>
  </section>
</main>

<style>
  .skip-link {
    position: absolute;
    top: -100px;
    left: 12px;
    background: white;
    padding: 12px;
    z-index: 2000;
  }
  .skip-link:focus {
    top: 12px;
  }
  main {
    display: grid;
    grid-template-columns: 330px minmax(0, 1fr);
    height: calc(100dvh - 94px);
    min-height: 560px;
  }
  aside {
    overflow: auto;
    background: #fff;
    border-right: 1px solid #d6e0d9;
    padding: 0 22px 24px;
    min-width: 0;
  }
  aside :global(section) {
    padding: 23px 0;
    border-bottom: 1px solid #e4ebe6;
  }
  .eyebrow {
    font-size: 10px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: #526b5c;
  }
  .access-panel :global(button) {
    padding: 5px 12px;
    min-height: 34px;
  }
  footer {
    font-size: 10px;
    color: #526b5c;
    padding: 25px 0;
  }
  .map-panel {
    position: relative;
    min-width: 0;
    display: flex;
    flex-direction: column;
    padding: 18px;
    gap: 0;
  }
  .map-caption {
    background: #fff;
    border: 1px solid #d4dfd7;
    border-bottom: 0;
    border-radius: 10px 10px 0 0;
    padding: 12px 16px;
    font-size: 12px;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .map-coordinate {
    margin-left: auto;
    color: #6e8074;
    font-size: 10px;
  }
  .status-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #36966a;
  }
  .map-panel :global(#map) {
    flex: 1;
    min-height: 360px;
    width: 100%;
    border: 1px solid #cfdbd3;
    z-index: 0;
  }
  .map-hint {
    margin: 0;
    background: #fff;
    padding: 10px 14px;
    border: 1px solid #d4dfd7;
    border-top: 0;
    border-radius: 0 0 10px 10px;
    font-size: 11px;
    color: #536d60;
  }
  @media (max-width: 720px) {
    .app-header {
      padding: 14px 18px;
      height: 80px;
    }
    .project-label {
      display: none;
    }
    main {
      display: flex;
      flex-direction: column;
      height: auto;
      min-height: 0;
    }
    aside {
      order: 2;
      overflow: visible;
      border-right: 0;
      padding: 0 18px 20px;
    }
    .map-panel {
      order: 1;
      height: 470px;
      padding: 12px;
    }
    .map-panel :global(#map) {
      min-height: 320px;
    }
    .map-coordinate {
      font-size: 9px;
    }
    .map-caption {
      padding: 10px;
    }
    .brand h1 {
      font-size: 21px;
    }
  }
</style>
