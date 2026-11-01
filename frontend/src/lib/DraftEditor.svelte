<script lang="ts">
  import type { AppModel } from "./model.svelte";
  let { model }: { model: AppModel } = $props();
  let lat = $state("52.339203");
  let lng = $state("35.350871");
  function addCoordinates(event: SubmitEvent) {
    event.preventDefault();
    if (!lat.trim() || !lng.trim()) {
      model.saveStatus = "Заполните широту и долготу.";
      return;
    }
    model.addDraft(Number(lat), Number(lng));
  }
</script>

<section aria-labelledby="editing-heading">
  <h2 id="editing-heading">Новые точки</h2>
  <label class="add-point-control layer-toggle"
    ><input
      type="checkbox"
      checked={model.addMode}
      disabled={!model.access.can_edit}
      onchange={(event) => model.setAddMode(event.currentTarget.checked)}
    />Добавлять точки на карту</label
  >
  <p class="hint">
    Включите режим и нажмите на карту. Черновики сохраняются при переключении
    режимов.
  </p>
  {#if model.addMode}<form class="coordinate-form" onsubmit={addCoordinates}>
      <label for="draft-lat"
        >Широта<input
          id="draft-lat"
          type="text"
          inputmode="decimal"
          required
          bind:value={lat}
        /></label
      >
      <label for="draft-lng"
        >Долгота<input
          id="draft-lng"
          type="text"
          inputmode="decimal"
          required
          bind:value={lng}
        /></label
      >
      <button type="submit">Добавить по координатам</button>
    </form>{/if}
  <div class="add-point-control-panel">
    {#each model.drafts as draft, index (draft.id)}
      <div class="change-add-point-name" data-draft-id={draft.id}>
        <label for={`name-${draft.id}`}
          >Точка {index + 1}<input
            id={`name-${draft.id}`}
            type="text"
            value={draft.name}
            maxlength="255"
            disabled={model.savingIds.includes(draft.id)}
            oninput={(event) =>
              model.renameDraft(draft.id, event.currentTarget.value)}
          /></label
        >
        <small>{draft.lat.toFixed(5)}, {draft.lng.toFixed(5)}</small>
        <button
          class="drop-new-point"
          type="button"
          disabled={model.savingIds.includes(draft.id)}
          aria-label={`Удалить черновик ${index + 1}`}
          onclick={() => model.dropDraft(draft.id)}>Удалить черновик</button
        >
      </div>
    {/each}
    {#if model.drafts.length}<button
        class="add-point-save primary"
        type="button"
        disabled={!model.access.can_edit || model.savePending}
        onclick={() => void model.save()}
        >{model.savePending
          ? "Сохранение…"
          : `Сохранить точки (${model.drafts.length})`}</button
      >{/if}
  </div>
  <p class="point-save-status" role="status">{model.saveStatus}</p>
</section>
