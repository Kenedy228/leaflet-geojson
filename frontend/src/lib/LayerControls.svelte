<script lang="ts">
  import type { AppModel } from "./model.svelte";
  import type { LayerName } from "./types";
  let { model }: { model: AppModel } = $props();
  const layers: { key: LayerName; label: string; description: string }[] = [
    {
      key: "points",
      label: "Точки",
      description: "OSM и общие пользовательские точки",
    },
    {
      key: "roads",
      label: "Дороги",
      description: "Линейные объекты OpenStreetMap",
    },
    {
      key: "polygons",
      label: "Полигоны",
      description: "Контуры территорий и зданий",
    },
  ];
</script>

<section aria-labelledby="layers-heading">
  <h2 id="layers-heading">Слои карты</h2>
  {#each layers as item (item.key)}
    <label class="layer-toggle"
      ><input
        class={`${item.key}-control`}
        type="checkbox"
        checked={model.layers[item.key].visible}
        onchange={(event) =>
          void model.toggleLayer(item.key, event.currentTarget.checked)}
      /><span
        ><strong>{item.label}</strong><small>{item.description}</small></span
      ></label
    >
    <div class="layer-status" data-layer={item.key}>
      {#if model.layers[item.key].loading}<p role="status">
          Загрузка: {item.label.toLowerCase()}…
        </p>{/if}
      {#if model.layers[item.key].error}<p role="alert">
          {model.layers[item.key].error}
        </p>
        <button
          type="button"
          aria-label={`Повторить загрузку: ${item.label}`}
          onclick={() => void model.toggleLayer(item.key, true)}
          >Повторить</button
        >{/if}
    </div>
  {/each}
</section>
