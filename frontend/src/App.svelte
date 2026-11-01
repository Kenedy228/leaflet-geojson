<script lang="ts">
  import { onMount } from "svelte";
  import { AppModel } from "./lib/model.svelte";
  import LeafletMap from "./lib/LeafletMap.svelte";
  import LayerControls from "./lib/LayerControls.svelte";
  import DraftEditor from "./lib/DraftEditor.svelte";
  import DistancePanel from "./lib/DistancePanel.svelte";
  const model = new AppModel();
  onMount(() => {
    void model.refreshSession().catch(() => {});
    return () => model.destroy();
  });
</script>

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
        {model.accessError ||
          (model.access.can_edit
            ? "Вы вошли как редактор общих точек."
            : model.access.editing_enabled
              ? "Просмотр. Для изменения точек войдите как редактор."
              : "Режим просмотра: запись отключена на сервере.")}
      </p>
      {#if model.accessError}<button
          type="button"
          disabled={model.accessPending}
          onclick={() => void model.refreshSession().catch(() => {})}
          >Проверить доступ</button
        >{/if}
      {#if model.access.editing_enabled && !model.access.can_edit}<a
          class="access-login"
          href="/login">Войти как редактор</a
        >{/if}
      {#if model.access.can_edit}<button
          class="access-logout"
          type="button"
          disabled={model.accessPending}
          onclick={() => void model.logout()}>Выйти</button
        >{/if}
    </section>
    <LayerControls {model} />
    <DistancePanel {model} />
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
          !model.access.can_edit ||
          model.deletePending}
        onclick={() => void model.deleteSelected()}>Удалить точку</button
      >
      <p class="delete-status" role="status">{model.deleteStatus}</p>
    </section>
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
  :global(*) {
    box-sizing: border-box;
  }
  :global(body) {
    margin: 0;
    background: #f3f5f3;
    color: #19312d;
    font-family: Inter, ui-sans-serif, system-ui, sans-serif;
    font-size: 14px;
    line-height: 1.5;
  }
  :global(button),
  :global(input) {
    font: inherit;
  }
  :global(button) {
    border: 1px solid #b7c9c2;
    border-radius: 7px;
    background: #fff;
    color: #193e34;
    padding: 8px 12px;
    cursor: pointer;
    min-height: 40px;
    max-width: 100%;
    white-space: normal;
  }
  :global(button:hover:not(:disabled)) {
    background: #e8f2eb;
  }
  :global(button:disabled) {
    opacity: 0.5;
    cursor: default;
  }
  :global(a) {
    color: #176447;
  }
  :global(:focus-visible) {
    outline: 3px solid #ca8f29;
    outline-offset: 3px;
  }
  :global(h2) {
    font-size: 15px;
    margin: 0 0 13px;
    font-weight: 700;
  }
  :global(p) {
    margin: 8px 0;
    overflow-wrap: anywhere;
  }
  :global(small) {
    font-size: 11px;
    color: #536d64;
  }
  :global(input[type="checkbox"]) {
    accent-color: #277957;
    width: 17px;
    height: 17px;
    flex-shrink: 0;
    margin: 3px 0;
  }
  :global(input[type="text"]),
  :global(input[type="number"]) {
    display: block;
    width: 100%;
    min-width: 0;
    margin: 5px 0;
    border: 1px solid #aebfb6;
    padding: 9px;
    border-radius: 6px;
    color: #19312d;
    background: white;
  }
  :global(.layer-toggle) {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    margin: 13px 0;
    cursor: pointer;
  }
  :global(.layer-toggle small) {
    display: block;
    margin-top: 2px;
  }
  :global(.hint) {
    font-size: 12px;
    color: #526c63;
  }
  :global(.button-row) {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  :global(.primary) {
    background: #216b4e;
    color: white;
    border-color: #216b4e;
    margin-top: 10px;
    width: 100%;
  }
  :global(.danger) {
    color: #9d302e;
  }
  :global([role="alert"]) {
    color: #a32929;
  }
  :global([role="status"]) {
    font-size: 12px;
  }
  :global(.coordinate-form) {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  :global(.coordinate-form button) {
    grid-column: 1/-1;
  }
  :global(.change-add-point-name) {
    padding: 12px 0;
    border-top: 1px dashed #c9d5ce;
  }
  :global(.change-add-point-name small) {
    display: block;
    margin-bottom: 7px;
  }
  :global(.points-distance-selected-list) {
    padding-left: 23px;
    overflow-wrap: anywhere;
  }
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
  .app-header {
    height: 94px;
    padding: 18px 30px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: #fff;
    border-bottom: 1px solid #d6e0d9;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 13px;
  }
  .brand-mark {
    background: #236a4e;
    color: white;
    font-size: 30px;
    border-radius: 12px;
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
  }
  .brand h1 {
    font-size: 24px;
    letter-spacing: -1px;
    margin: 0;
  }
  .brand p {
    font-size: 11px;
    color: #63766d;
    margin: 0;
  }
  .project-label {
    font-size: 11px;
    letter-spacing: 0.06em;
    color: #667a6f;
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
