<script lang="ts">
  import type { AppModel } from "./model.svelte";
  let { model }: { model: AppModel } = $props();
</script>

<section class="points-distance" aria-labelledby="distance-heading">
  <h2 id="distance-heading">Геодезическое расстояние</h2>
  <label class="layer-toggle"
    ><input
      class="points-distance-control"
      type="checkbox"
      checked={model.distanceMode}
      disabled={!model.layers.points.visible}
      onchange={(event) => model.setDistanceMode(event.currentTarget.checked)}
    />Выбирать точки для расчёта</label
  >
  <p class="hint">
    Выберите точки по порядку. Расстояние по сфероиду WGS84 в метрах; сумма
    прямых участков, не маршрут по дорогам.
  </p>
  <ol class="points-distance-selected-list">
    {#each model.selected as point (point.id)}<li>{point.name}</li>{/each}
  </ol>
  <div class="button-row">
    <button
      class="points-distance-calculate-control"
      type="button"
      disabled={!model.distanceMode || model.selected.length < 2}
      onclick={() => void model.calculate()}
      >{model.distancePending ? "Пересчитать" : "Рассчитать"}</button
    >
    <button
      class="points-distance-reset-control"
      type="button"
      disabled={!model.selected.length && !model.distancePending}
      onclick={() => model.clearDistance()}>Сбросить</button
    >
  </div>
  <p class="distance-status" role="status">
    {model.distancePending
      ? "Расчёт расстояния…"
      : model.distance
        ? `Общая дистанция: ${model.distance.totalDistance.toFixed(2)} м`
        : ""}
  </p>
  {#if model.distanceError}<p class="distance-error" role="alert">
      {model.distanceError}
    </p>{/if}
</section>
