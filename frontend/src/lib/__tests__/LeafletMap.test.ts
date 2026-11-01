import { afterEach, describe, expect, it, vi } from "vitest";
import { flushSync, mount, unmount } from "svelte";
import L from "leaflet";
import LeafletMap from "../LeafletMap.svelte";
import { AppModel } from "../model.svelte";
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
  document.body.replaceChildren();
});

describe("Leaflet component lifecycle", () => {
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
