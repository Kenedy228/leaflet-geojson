<script lang="ts">
  import { onMount } from "svelte";
  import L from "leaflet";
  import "leaflet/dist/leaflet.css";
  import type { AppModel } from "./model.svelte";

  let { model }: { model: AppModel } = $props();
  let container: HTMLDivElement;
  let map = $state.raw<L.Map | null>(null);
  let pointMarkers = $state.raw<Record<string, L.CircleMarker>>({});

  function popup(text: string): HTMLElement {
    const element = document.createElement("span");
    element.textContent = text;
    return element;
  }

  function accessiblePath(
    layer: L.Path,
    label: string,
    attributes: Record<string, string>,
    signal: AbortSignal,
    activate: () => void = () => {
      layer.openPopup();
    },
  ) {
    const element = layer.getElement();
    if (!element) return;
    element.setAttribute("tabindex", "0");
    element.setAttribute("role", "button");
    element.setAttribute("aria-label", label);
    for (const [name, value] of Object.entries(attributes))
      element.setAttribute(name, value);
    element.addEventListener(
      "keydown",
      (event) => {
        if (
          event instanceof KeyboardEvent &&
          (event.key === "Enter" || event.key === " ")
        ) {
          event.preventDefault();
          event.stopPropagation();
          activate();
        }
      },
      { signal },
    );
  }

  onMount(() => {
    const instance = L.map(container, { preferCanvas: false }).setView(
      [52.339203, 35.350871],
      13,
    );
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(instance);
    const addPoint = (event: L.LeafletMouseEvent) => {
      if (model.addMode) model.addDraft(event.latlng.lat, event.latlng.lng);
    };
    instance.on("click", addPoint);
    const observer = new ResizeObserver(() => instance.invalidateSize());
    observer.observe(container);
    map = instance;
    return () => {
      observer.disconnect();
      instance.off("click", addPoint);
      instance.remove();
      map = null;
    };
  });

  $effect(() => {
    const instance = map;
    const points = model.points;
    if (!instance) return;
    const group = L.layerGroup().addTo(instance);
    const controller = new AbortController();
    const markers: Record<string, L.CircleMarker> = {};
    for (const point of points) {
      if (point.geojson.type !== "Point") continue;
      const [lng, lat] = point.geojson.coordinates;
      if (lng === undefined || lat === undefined) continue;
      const marker = L.circleMarker([lat, lng], {
        radius: 8,
        weight: 2,
        color: "#173d40",
        fillColor: point.source === "user" ? "#138075" : "#ed8b38",
        fillOpacity: 0.95,
        bubblingMouseEvents: false,
      })
        .bindPopup(popup(point.name || "Без названия"))
        .addTo(group);
      markers[point.id] = marker;
      const activate = () => {
        model.clickPoint(point);
        marker.openPopup();
      };
      marker.on("click", activate);
      accessiblePath(
        marker,
        point.name || "Без названия",
        { "data-point-id": point.id },
        controller.signal,
        activate,
      );
    }
    pointMarkers = markers;
    return () => {
      controller.abort();
      group.remove();
    };
  });

  $effect(() => {
    const selected = new Set(model.selected.map((point) => point.id));
    for (const [id, marker] of Object.entries(pointMarkers)) {
      const point = model.points.find((item) => item.id === id);
      marker.setStyle({
        fillColor: selected.has(id)
          ? "#173d40"
          : point?.source === "user"
            ? "#138075"
            : "#ed8b38",
      });
    }
  });

  $effect(() => {
    const instance = map;
    const drafts = model.drafts;
    if (!instance) return;
    const group = L.layerGroup().addTo(instance);
    const controller = new AbortController();
    for (const draft of drafts) {
      const marker = L.circleMarker([draft.lat, draft.lng], {
        radius: 8,
        weight: 2,
        color: "#173d40",
        fillColor: "#a6d96a",
        fillOpacity: 0.95,
        bubblingMouseEvents: false,
      })
        .bindPopup(popup(draft.name || "Новая точка"))
        .addTo(group);
      accessiblePath(
        marker,
        draft.name || "Новая точка",
        { "data-draft-id": draft.id },
        controller.signal,
      );
    }
    return () => {
      controller.abort();
      group.remove();
    };
  });

  $effect(() => {
    const instance = map;
    const roads = model.layers.roads.data;
    const polygons = model.layers.polygons.data;
    if (!instance) return;
    const group = L.layerGroup().addTo(instance);
    const controller = new AbortController();
    for (const [kind, items] of [
      ["roads", roads],
      ["polygons", polygons],
    ] as const) {
      for (const item of items) {
        const layer = L.geoJSON(item.geojson, {
          style: {
            color: kind === "roads" ? "#bb503b" : "#527f40",
            weight: 2,
            fillOpacity: 0.18,
          },
          onEachFeature: (_feature, child) => {
            child.bindPopup(popup(item.name || "Без названия"));
          },
        }).addTo(group);
        layer.eachLayer((child) => {
          if (child instanceof L.Path)
            accessiblePath(
              child,
              item.name || "Без названия",
              { "data-layer": kind },
              controller.signal,
            );
        });
      }
    }
    return () => {
      controller.abort();
      group.remove();
    };
  });

  $effect(() => {
    const instance = map;
    const result = model.distance;
    const selected = model.selected;
    if (!instance) return;
    const group = L.layerGroup().addTo(instance);
    const controller = new AbortController();
    result?.lines.forEach((segment, index) => {
      const label = `Дистанция участка ${selected[index]?.name ?? index + 1}–${selected[index + 1]?.name ?? index + 2}: ${segment.distance.toFixed(2)} м. Общая дистанция: ${result.totalDistance.toFixed(2)} м`;
      const line = L.polyline(
        [
          [segment.from[1], segment.from[0]],
          [segment.to[1], segment.to[0]],
        ],
        {
          color: "#b33341",
          weight: 4,
          bubblingMouseEvents: false,
        },
      )
        .bindPopup(popup(label))
        .addTo(group);
      accessiblePath(
        line,
        label,
        { "data-distance-line": String(index) },
        controller.signal,
      );
    });
    return () => {
      controller.abort();
      group.remove();
    };
  });
</script>

<div
  id="map"
  bind:this={container}
  role="region"
  aria-label="Карта географических объектов"
></div>

<style>
  #map {
    width: 100%;
    height: 100%;
    min-height: 300px;
    isolation: isolate;
    background: #e9ede3;
  }
  #map :global(.leaflet-interactive:focus-visible) {
    outline: 3px solid #173d40;
    outline-offset: 3px;
  }
  #map :global(.leaflet-popup-content) {
    overflow-wrap: anywhere;
    max-width: 250px;
  }
</style>
