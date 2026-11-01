<script lang="ts">
  import { onMount } from "svelte";
  import L from "leaflet";
  import type { Geometry, LineString, Polygon } from "geojson";
  import "leaflet/dist/leaflet.css";
  import type { AppModel } from "./model.svelte";

  let { model }: { model: AppModel } = $props();
  let container: HTMLDivElement;
  let map = $state.raw<L.Map | null>(null);
  let pointMarkers = $state.raw<Record<string, L.CircleMarker[]>>({});
  let view = $state({ center: 35.350871, west: -180, east: 180 });

  // Rendering uses local world copies; transport and selection keep WGS84 coordinates.
  const nearestLongitude = (lng: number, center: number) =>
    lng + 360 * Math.round((center - lng) / 360);

  function copyOffsets(west: number, east: number, extent: typeof view) {
    const first = Math.ceil((extent.west - east) / 360);
    const last = Math.floor((extent.east - west) / 360);
    return Array.from(
      { length: Math.max(0, last - first + 1) },
      (_, index) => 360 * (first + index),
    );
  }

  // Every connected reference component keeps one offset, including polygon holes.
  // Its planar shape is supplied by PostGIS; only distance segments unwrap endpoints.
  function referenceComponents(geometry: Geometry): (LineString | Polygon)[] {
    switch (geometry.type) {
      case "LineString":
      case "Polygon":
        return [geometry];
      case "MultiLineString":
        return geometry.coordinates.map((coordinates) => ({
          type: "LineString",
          coordinates,
        }));
      case "MultiPolygon":
        return geometry.coordinates.map((coordinates) => ({
          type: "Polygon",
          coordinates,
        }));
      default:
        return [];
    }
  }

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
      if (model.addMode)
        model.addDraft(event.latlng.lat, event.latlng.wrap().lng);
    };
    const updateViewport = () => {
      const bounds = instance.getBounds();
      if (bounds.getWest() === bounds.getEast()) return;
      const center = instance.getCenter().lng;
      view = { center, west: bounds.getWest(), east: bounds.getEast() };
      const south = Math.max(-90, bounds.getSouth());
      const north = Math.min(90, bounds.getNorth());
      const width = bounds.getEast() - bounds.getWest();
      const wrap = (lng: number) =>
        Number((((((lng + 180) % 360) + 360) % 360) - 180).toFixed(9));
      const west = width >= 360 ? -180 : wrap(bounds.getWest());
      const east = width >= 360 ? 180 : wrap(bounds.getEast());
      if (south >= north || west === east) return;
      model.setViewport({
        bbox: [west, south, east, north],
        zoom: Math.max(0, Math.min(22, Math.round(instance.getZoom()))),
      });
    };
    instance.on("click", addPoint);
    instance.on("moveend zoomend", updateViewport);
    updateViewport();
    const observer = new ResizeObserver(() => instance.invalidateSize());
    observer.observe(container);
    map = instance;
    return () => {
      observer.disconnect();
      instance.off("click", addPoint);
      instance.off("moveend zoomend", updateViewport);
      model.cancelViewportRefresh();
      instance.remove();
      map = null;
    };
  });

  $effect(() => {
    const instance = map;
    const points = model.points;
    const extent = view;
    if (!instance) return;
    const group = L.layerGroup().addTo(instance);
    const controller = new AbortController();
    const markers: Record<string, L.CircleMarker[]> = {};
    for (const point of points) {
      if (point.geojson.type !== "Point") continue;
      const [lng, lat] = point.geojson.coordinates;
      if (lng === undefined || lat === undefined) continue;
      markers[point.id] = [];
      for (const offset of copyOffsets(lng, lng, extent)) {
        const marker = L.circleMarker([lat, lng + offset], {
          radius: 8,
          weight: 2,
          color: "#173d40",
          fillColor: point.source === "user" ? "#138075" : "#ed8b38",
          fillOpacity: 0.95,
          bubblingMouseEvents: false,
        })
          .bindPopup(popup(point.name || "Без названия"))
          .addTo(group);
        markers[point.id]!.push(marker);
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
    }
    pointMarkers = markers;
    return () => {
      controller.abort();
      group.remove();
    };
  });

  $effect(() => {
    const selected = new Set(model.selected.map((point) => point.id));
    for (const [id, markers] of Object.entries(pointMarkers)) {
      const point = model.points.find((item) => item.id === id);
      for (const marker of markers)
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
    const extent = view;
    if (!instance) return;
    const group = L.layerGroup().addTo(instance);
    const controller = new AbortController();
    for (const draft of drafts) {
      for (const offset of copyOffsets(draft.lng, draft.lng, extent)) {
        const marker = L.circleMarker([draft.lat, draft.lng + offset], {
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
    const extent = view;
    if (!instance) return;
    const group = L.layerGroup().addTo(instance);
    const controller = new AbortController();
    for (const [kind, items] of [
      ["roads", roads],
      ["polygons", polygons],
    ] as const) {
      for (const item of items) {
        for (const component of referenceComponents(item.geojson)) {
          const coordinates =
            component.type === "LineString"
              ? component.coordinates
              : component.coordinates.flat();
          const west = Math.min(...coordinates.map((position) => position[0]!));
          const east = Math.max(...coordinates.map((position) => position[0]!));
          const baseOffset =
            360 * Math.round((extent.center - (west + east) / 2) / 360);
          for (const copy of copyOffsets(
            west + baseOffset,
            east + baseOffset,
            extent,
          )) {
            const offset = baseOffset + copy;
            const layer = L.geoJSON(component, {
              coordsToLatLng: (coordinates) =>
                L.latLng(coordinates[1]!, coordinates[0]! + offset),
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
    const extent = view;
    if (!instance) return;
    const group = L.layerGroup().addTo(instance);
    const controller = new AbortController();
    result?.lines.forEach((segment, index) => {
      const label = `Дистанция участка ${selected[index]?.name ?? index + 1}–${selected[index + 1]?.name ?? index + 2}: ${segment.distance.toFixed(2)} м. Общая дистанция: ${result.totalDistance.toFixed(2)} м`;
      const from = nearestLongitude(segment.from[0], extent.center);
      const to = nearestLongitude(segment.to[0], from);
      for (const offset of copyOffsets(
        Math.min(from, to),
        Math.max(from, to),
        extent,
      )) {
        const line = L.polyline(
          [
            [segment.from[1], from + offset],
            [segment.to[1], to + offset],
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
      }
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
