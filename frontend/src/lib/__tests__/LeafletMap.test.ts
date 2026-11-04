import { afterEach, describe, expect, it, vi } from "vitest";
import { flushSync, mount, unmount } from "svelte";
import L from "leaflet";
import LeafletMap from "../LeafletMap.svelte";
import { AppModel } from "../model.svelte";
import { Api } from "../api";
import type { GeoItem } from "../types";

const point: GeoItem = {
  id: "osm:9007199254740993",
  source: "osm",
  name: "<img src=x onerror=alert(1)>",
  geojson: { type: "Point", coordinates: [35.350871, 52.339203] },
};

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
  document.body.replaceChildren();
});

describe("Leaflet component lifecycle", () => {
  it("preserves connected reference geometry and polygon holes across world-copy boundaries", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        disconnect() {}
      },
    );
    const svg = L.Browser.svg;
    Object.defineProperty(L.Browser, "svg", {
      value: true,
      configurable: true,
    });
    vi.spyOn(L.Map.prototype, "getSize").mockImplementation(() =>
      L.point(800, 600),
    );
    const views = vi.spyOn(L.Map.prototype, "setView");
    const model = new AppModel();
    const target = document.createElement("div");
    document.body.append(target);
    const component = mount(LeafletMap, { target, props: { model } });
    flushSync();
    const map = views.mock.instances[0] as L.Map;
    map.setView([0, 181], 0, { animate: false });
    const polygon: number[][][] = [
      [
        [0, 0],
        [2, 0],
        [2, 2],
        [0, 2],
        [0, 0],
      ],
      [
        [0.5, 0.5],
        [1.5, 0.5],
        [1.5, 1.5],
        [0.5, 0.5],
      ],
    ];
    model.layers.roads.data = [
      {
        id: "road:1",
        name: "Line",
        geojson: {
          type: "LineString",
          coordinates: [
            [0, 0],
            [2, 0],
          ],
        },
      },
      {
        id: "road:2",
        name: "MultiLine",
        geojson: {
          type: "MultiLineString",
          coordinates: [
            [
              [0, 0],
              [2, 0],
            ],
            [
              [4, 0],
              [6, 0],
            ],
          ],
        },
      },
    ];
    model.layers.polygons.data = [
      {
        id: "polygon:1",
        name: "Polygon",
        geojson: { type: "Polygon", coordinates: polygon },
      },
      {
        id: "polygon:2",
        name: "MultiPolygon",
        geojson: {
          type: "MultiPolygon",
          coordinates: [
            polygon,
            polygon.map((ring) => ring.map(([lng, lat]) => [lng! + 4, lat!])),
          ],
        },
      },
    ];
    const canonical = JSON.stringify([
      model.layers.roads.data,
      model.layers.polygons.data,
    ]);
    flushSync();
    const paths: L.Polyline[] = [];
    map.eachLayer((layer) => {
      if (
        layer instanceof L.Polyline &&
        layer.getElement()?.hasAttribute("data-layer")
      )
        paths.push(layer);
    });
    expect(paths.length).toBeGreaterThan(6);
    for (const path of paths) {
      expect(path.getBounds().getEast() - path.getBounds().getWest()).toBe(2);
      if (path instanceof L.Polygon) {
        const rings = path.getLatLngs() as L.LatLng[][];
        expect(rings).toHaveLength(2);
        expect(
          Math.max(...rings[1]!.map((p) => p.lng)) -
            Math.min(...rings[1]!.map((p) => p.lng)),
        ).toBe(1);
        expect(rings[1]![0]!.lng - rings[0]![0]!.lng).toBe(0.5);
      }
    }
    expect(
      JSON.stringify([model.layers.roads.data, model.layers.polygons.data]),
    ).toBe(canonical);
    await unmount(component);
    model.destroy();
    Object.defineProperty(L.Browser, "svg", { value: svg, configurable: true });
  });

  it("renders canonical objects in the visible world copy and keeps distance snapshots canonical", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        disconnect() {}
      },
    );
    const svg = L.Browser.svg;
    Object.defineProperty(L.Browser, "svg", {
      value: true,
      configurable: true,
    });
    vi.spyOn(L.Map.prototype, "getSize").mockImplementation(() =>
      L.point(800, 600),
    );
    const views = vi.spyOn(L.Map.prototype, "setView");
    const api = new Api();
    vi.spyOn(api, "layer").mockResolvedValue([]);
    const model = new AppModel(api);
    const target = document.createElement("div");
    document.body.append(target);
    const component = mount(LeafletMap, { target, props: { model } });
    flushSync();
    const map = views.mock.instances[0] as L.Map;
    map.setView([0, 181], 4, { animate: false });
    const canonical: GeoItem = {
      ...point,
      geojson: { type: "Point", coordinates: [-179, 0] },
    };
    model.layers.points.visible = true;
    model.layers.points.data = [canonical];
    model.drafts = [{ id: "draft:1", name: "Draft", lng: -179, lat: 0 }];
    model.layers.roads.data = [
      {
        id: "road:1",
        name: "Road",
        geojson: {
          type: "LineString",
          coordinates: [
            [-179, 0],
            [-178, 1],
          ],
        },
      },
    ];
    model.layers.polygons.data = [
      {
        id: "polygon:1",
        name: "Polygon",
        geojson: {
          type: "Polygon",
          coordinates: [
            [
              [-179, 0],
              [-178, 0],
              [-178, 1],
              [-179, 0],
            ],
          ],
        },
      },
    ];
    model.setDistanceMode(true);
    model.clickPoint(canonical);
    model.clickPoint({
      ...canonical,
      id: "osm:2",
      geojson: { type: "Point", coordinates: [179, 0] },
    });
    model.distance = {
      lines: [{ from: [-179, 0], to: [179, 0], distance: 222639 }],
      totalDistance: 222639,
    };
    flushSync();
    expect(model.points, JSON.stringify(model.viewport)).toHaveLength(1);
    const paths = () => {
      const layers: L.Path[] = [];
      map.eachLayer((layer) => {
        if (layer instanceof L.Path) layers.push(layer);
      });
      return layers;
    };
    const marker = paths().find((layer) =>
      layer.getElement()?.hasAttribute("data-point-id"),
    ) as L.CircleMarker;
    expect(map.latLngToContainerPoint(marker.getLatLng()).x).toBeCloseTo(
      400,
      0,
    );
    const draft = paths().find((layer) =>
      layer.getElement()?.hasAttribute("data-draft-id"),
    ) as L.CircleMarker;
    expect(draft.getLatLng().lng).toBe(181);
    for (const kind of ["roads", "polygons"]) {
      const reference = paths().find(
        (layer) => layer.getElement()?.getAttribute("data-layer") === kind,
      ) as L.Polyline;
      expect(reference.getBounds().getWest()).toBe(181);
      expect(reference.getBounds().getEast()).toBe(182);
    }
    const route = paths().find((layer) =>
      layer.getElement()?.hasAttribute("data-distance-line"),
    ) as L.Polyline;
    expect(route.getBounds().getEast() - route.getBounds().getWest()).toBe(2);
    const viewport = JSON.parse(JSON.stringify(model.viewport));
    map.setView([0, 541], 4, { animate: false });
    flushSync();
    expect(model.viewport).toEqual(viewport);
    const shifted = paths().find((layer) =>
      layer.getElement()?.hasAttribute("data-point-id"),
    ) as L.CircleMarker;
    expect(shifted.getLatLng().lng).toBe(541);
    expect(model.selected[0]?.geojson).toEqual(canonical.geojson);
    expect(model.distance.lines[0]?.from).toEqual([-179, 0]);
    expect(model.drafts[0]?.lng).toBe(-179);
    map.setView([0, 541], 0, { animate: false });
    model.layers.points.data = [canonical];
    flushSync();
    const copies = paths().filter((layer) =>
      layer.getElement()?.hasAttribute("data-point-id"),
    ) as L.CircleMarker[];
    expect(copies).toHaveLength(3);
    for (const copy of copies) {
      const x = map.latLngToContainerPoint(copy.getLatLng()).x;
      expect(x).toBeGreaterThanOrEqual(0);
      expect(x).toBeLessThanOrEqual(800);
    }
    model.access = {
      editing_enabled: true,
      can_edit: true,
      csrf_header: "X-CSRF-TOKEN",
      csrf_token: "token",
    };
    model.setAddMode(true);
    map.fire("click", { latlng: L.latLng(0, 541) });
    expect(model.drafts.at(-1)?.lng).toBe(-179);
    await unmount(component);
    await vi.advanceTimersByTimeAsync(150);
    expect(map.listens("moveend")).toBe(false);
    model.destroy();
    Object.defineProperty(L.Browser, "svg", { value: svg, configurable: true });
  });

  it("reads viewport bounds on movement and detaches listeners and debounced requests", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        disconnect() {}
      },
    );
    const api = new Api();
    const get = vi.spyOn(api, "layer").mockResolvedValue([]);
    const model = new AppModel(api);
    await model.toggleLayer("roads", true);
    const bounds = vi
      .spyOn(L.Map.prototype, "getBounds")
      .mockReturnValue(L.latLngBounds([50, 170], [60, 190]));
    const zoom = vi.spyOn(L.Map.prototype, "getZoom").mockReturnValue(8);
    const target = document.createElement("div");
    document.body.append(target);
    const component = mount(LeafletMap, { target, props: { model } });
    flushSync();
    expect(model.viewport).toEqual({ bbox: [170, 50, -170, 60], zoom: 8 });
    await vi.advanceTimersByTimeAsync(150);
    expect(get).toHaveBeenCalledTimes(2);
    const instance = bounds.mock.instances[0] as L.Map;
    bounds.mockReturnValue(L.latLngBounds([40, 30], [50, 40]));
    zoom.mockReturnValue(9);
    instance.fire("moveend");
    instance.fire("zoomend");
    expect(model.viewport).toEqual({ bbox: [30, 40, 40, 50], zoom: 9 });
    await unmount(component);
    expect(instance.listens("moveend")).toBe(false);
    expect(instance.listens("zoomend")).toBe(false);
    await vi.advanceTimersByTimeAsync(150);
    expect(get).toHaveBeenCalledTimes(2);
    model.destroy();
    vi.useRealTimers();
  });
  it("removes map resources and supports a second mount on the same container", async () => {
    const disconnect = vi.fn();
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        disconnect = disconnect;
      },
    );
    // jsdom has no SVG layout; Leaflet still creates and handles real SVG paths.
    const originalSvg = L.Browser.svg;
    Object.defineProperty(L.Browser, "svg", {
      value: true,
      configurable: true,
    });
    const remove = vi.spyOn(L.Map.prototype, "remove");
    const target = document.createElement("div");
    document.body.append(target);
    const model = new AppModel();
    model.layers.points.visible = true;
    model.layers.points.data = [point];
    const first = mount(LeafletMap, { target, props: { model } });
    flushSync();
    const marker = target.querySelector("[data-point-id]");
    expect(marker?.getAttribute("data-point-id")).toBe(point.id);
    expect(marker?.getAttribute("aria-label")).toBe(point.name);
    marker?.dispatchEvent(
      new KeyboardEvent("keydown", { key: "Enter", bubbles: true }),
    );
    flushSync();
    expect(target.querySelector(".leaflet-popup-content")?.textContent).toBe(
      point.name,
    );
    expect(target.querySelector(".leaflet-popup-content img")).toBeNull();
    await unmount(first);
    expect(remove).toHaveBeenCalledTimes(1);
    expect(disconnect).toHaveBeenCalledTimes(1);
    expect(target.querySelector(".leaflet-container")).toBeNull();
    const second = mount(LeafletMap, { target, props: { model } });
    flushSync();
    expect(target.querySelectorAll("[data-point-id]")).toHaveLength(1);
    await unmount(second);
    expect(remove).toHaveBeenCalledTimes(2);
    expect(disconnect).toHaveBeenCalledTimes(2);
    model.destroy();
    Object.defineProperty(L.Browser, "svg", {
      value: originalSvg,
      configurable: true,
    });
  });
});
