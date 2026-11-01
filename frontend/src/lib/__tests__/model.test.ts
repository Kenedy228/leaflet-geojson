import { describe, expect, it, vi } from "vitest";
import { AppModel } from "../model.svelte";
import { Api, ApiError, decodeItems, decodeDistance } from "../api";
import type { GeoItem } from "../types";
const point: GeoItem = {
  id: "user:00000000-0000-0000-0000-000000000001",
  source: "user",
  name: "A",
  geojson: { type: "Point", coordinates: [35, 52] },
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
const session = {
  editing_enabled: true,
  can_edit: true,
  csrf_header: "X-CSRF-TOKEN",
  csrf_token: "token",
};
describe("reactive application state", () => {
  it("ignores a late hidden layer response", async () => {
    const d = deferred<GeoItem[]>();
    const api = new Api();
    vi.spyOn(api, "layer").mockReturnValue(d.promise);
    const m = new AppModel(api);
    const loading = m.toggleLayer("points", true);
    await m.toggleLayer("points", false);
    d.resolve([point]);
    await loading;
    expect(m.points).toEqual([]);
    m.destroy();
  });
  it("saves an immutable batch, blocks duplicate writes and preserves later drafts", async () => {
    const d = deferred<GeoItem[]>();
    const api = new Api();
    vi.spyOn(api, "session").mockResolvedValue(session);
    const save = vi.spyOn(api, "save").mockReturnValue(d.promise);
    const m = new AppModel(api);
    await m.refreshSession();
    m.setAddMode(true);
    m.addDraft(52, 35);
    const id = m.drafts[0]!.id;
    const pending = m.save();
    m.renameDraft(id, "changed");
    m.addDraft(53, 36);
    void m.save();
    await vi.waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    expect(save.mock.calls[0]![0][0]!.name).toBe("Новая точка");
    d.resolve([point]);
    await pending;
    expect(m.drafts).toHaveLength(1);
    expect(m.drafts[0]!.lat).toBe(53);
    m.destroy();
  });
  it("invalidates pending distance immediately when selection changes", async () => {
    const d = deferred<{ lines: []; totalDistance: number }>();
    const api = new Api();
    vi.spyOn(api, "distance").mockReturnValue(d.promise);
    const m = new AppModel(api);
    m.layers.points.visible = true;
    m.setDistanceMode(true);
    m.clickPoint(point);
    m.clickPoint({ ...point, id: "osm:2" });
    const request = m.calculate();
    m.clearDistance();
    d.resolve({ lines: [], totalDistance: 99 });
    await request;
    expect(m.distance).toBeNull();
    expect(m.selected).toEqual([]);
    m.destroy();
  });
  it("preserves drafts on failure, mode exit and logout", async () => {
    const api = new Api();
    vi.spyOn(api, "session").mockResolvedValue(session);
    vi.spyOn(api, "save").mockRejectedValue(new Error("failure"));
    vi.spyOn(api, "logout").mockImplementation(async () => {
      vi.mocked(api.session).mockResolvedValue({ ...session, can_edit: false });
    });
    const m = new AppModel(api);
    await m.refreshSession();
    m.setAddMode(true);
    m.addDraft(52, 35);
    await m.save();
    m.setAddMode(false);
    await m.logout();
    expect(m.drafts).toHaveLength(1);
    expect(m.access.can_edit).toBe(false);
    m.destroy();
  });
  it.each([404, 500])(
    "handles delete HTTP %i without losing retryable targets",
    async (status) => {
      const api = new Api();
      vi.spyOn(api, "session").mockResolvedValue(session);
      vi.spyOn(api, "remove").mockRejectedValue(
        new ApiError(status, "Cannot delete"),
      );
      const m = new AppModel(api);
      await m.refreshSession();
      m.layers.points.visible = true;
      m.layers.points.data = [point];
      m.clickPoint(point);
      await m.deleteSelected();
      expect(m.deleteTarget?.id).toBe(status === 404 ? undefined : point.id);
      expect(m.points).toHaveLength(status === 404 ? 0 : 1);
      expect(m.deleteStatus).toBe("Cannot delete");
      m.destroy();
    },
  );
  it("merges confirmed saves over a delayed GET and retains deletion tombstones", async () => {
    const api = new Api();
    const d = deferred<GeoItem[]>();
    vi.spyOn(api, "layer").mockReturnValue(d.promise);
    vi.spyOn(api, "session").mockResolvedValue(session);
    vi.spyOn(api, "save").mockResolvedValue([point]);
    vi.spyOn(api, "remove").mockResolvedValue(undefined);
    const m = new AppModel(api);
    await m.refreshSession();
    const loading = m.toggleLayer("points", true);
    m.setAddMode(true);
    m.addDraft(52, 35);
    await m.save();
    d.resolve([]);
    await loading;
    expect(m.points).toEqual([point]);
    m.setAddMode(false);
    m.clickPoint(point);
    await m.deleteSelected();
    expect(m.points).toEqual([]);
    m.destroy();
  });
});
describe("runtime API decoding", () => {
  it("rejects duplicate IDs, wrong coordinate types and malformed geometry", () => {
    expect(() =>
      decodeItems(
        [{ ...point, geojson: JSON.stringify(point.geojson) }, { ...point }],
        "points",
      ),
    ).toThrow();
    expect(() =>
      decodeItems(
        [{ ...point, geojson: { type: "Point", coordinates: ["35", 52] } }],
        "points",
      ),
    ).toThrow();
    expect(() =>
      decodeItems(
        [
          {
            name: "road",
            geojson: { type: "LineString", coordinates: [[35, 52]] },
          },
        ],
        "roads",
      ),
    ).toThrow();
  });
  it("rejects distance segments inconsistent with requested coordinates", () => {
    expect(() =>
      decodeDistance(
        {
          lines: [{ from: [0, 0], to: [1, 1], distance: 1 }],
          totalDistance: 1,
        },
        [
          [35, 52],
          [36, 53],
        ],
      ),
    ).toThrow();
  });
});
describe("authoritative refresh races", () => {
  it("does not restore editor access or send a pending stale save after logout", async () => {
    const api = new Api();
    const old = deferred<typeof session>();
    const get = vi.spyOn(api, "session").mockResolvedValue(session);
    const write = vi.spyOn(api, "save").mockResolvedValue([point]);
    vi.spyOn(api, "logout").mockImplementation(async () => {
      get.mockResolvedValue({ ...session, can_edit: false });
    });
    const m = new AppModel(api);
    await m.refreshSession();
    m.setAddMode(true);
    m.addDraft(52, 35);
    get.mockReturnValueOnce(old.promise);
    const pending = m.save();
    await m.logout();
    old.resolve(session);
    await pending;
    expect(m.access.can_edit).toBe(false);
    expect(write).not.toHaveBeenCalled();
    expect(m.drafts).toHaveLength(1);
    m.destroy();
  });
  it("keeps the newest session when two refreshes finish in reverse order", async () => {
    const api = new Api();
    const old = deferred<typeof session>();
    const get = vi
      .spyOn(api, "session")
      .mockReturnValueOnce(old.promise)
      .mockResolvedValue({ ...session, can_edit: false });
    const m = new AppModel(api);
    const first = m.refreshSession().catch(() => undefined);
    await m.refreshSession();
    old.resolve(session);
    await first;
    expect(get).toHaveBeenCalledTimes(2);
    expect(m.access.can_edit).toBe(false);
    m.destroy();
  });
  it("retires saved overlays and deletion tombstones after a newer authoritative GET", async () => {
    const api = new Api();
    vi.spyOn(api, "session").mockResolvedValue(session);
    vi.spyOn(api, "save").mockResolvedValue([point]);
    vi.spyOn(api, "remove").mockResolvedValue(undefined);
    const get = vi.spyOn(api, "layer").mockResolvedValue([]);
    const m = new AppModel(api);
    await m.refreshSession();
    await m.toggleLayer("points", true);
    m.setAddMode(true);
    m.addDraft(52, 35);
    await m.save();
    expect(m.points).toHaveLength(1);
    await m.toggleLayer("points", false);
    await m.toggleLayer("points", true);
    expect(m.points).toEqual([]);
    get.mockResolvedValue([point]);
    await m.toggleLayer("points", true);
    m.setAddMode(false);
    m.clickPoint(point);
    await m.deleteSelected();
    expect(m.points).toEqual([]);
    await m.toggleLayer("points", true);
    expect(m.points).toEqual([point]);
    m.destroy();
  });
});
describe("logout and rejected write preflight", () => {
  it.each(["save", "delete"] as const)(
    "does not let stale %s recovery cancel logout",
    async (operation) => {
      const api = new Api();
      const old = deferred<typeof session>();
      const preflight = deferred<typeof session>();
      const get = vi.spyOn(api, "session").mockResolvedValue(session);
      const logout = vi.spyOn(api, "logout").mockImplementation(async () => {
        get.mockResolvedValue({ ...session, can_edit: false });
      });
      const save = vi.spyOn(api, "save").mockResolvedValue([point]);
      const remove = vi.spyOn(api, "remove").mockResolvedValue(undefined);
      const m = new AppModel(api);
      await m.refreshSession();
      m.setAddMode(true);
      m.addDraft(52, 35);
      m.setAddMode(false);
      m.clickPoint(point);
      get
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(preflight.promise);
      const writing = operation === "save" ? m.save() : m.deleteSelected();
      const leaving = m.logout();
      old.resolve(session);
      await writing;
      expect(get).toHaveBeenCalledTimes(3);
      preflight.resolve(session);
      await leaving;
      expect(logout).toHaveBeenCalledTimes(1);
      expect(save).not.toHaveBeenCalled();
      expect(remove).not.toHaveBeenCalled();
      expect(m.access.can_edit).toBe(false);
      expect(get).toHaveBeenCalledTimes(4);
      m.destroy();
    },
  );
});

describe("viewport layer loading", () => {
  it("debounces movement, aborts old requests and ignores late results while retaining route and drafts", async () => {
    vi.useFakeTimers();
    const api = new Api();
    const old = deferred<GeoItem[]>();
    const get = vi
      .spyOn(api, "layer")
      .mockReturnValueOnce(old.promise)
      .mockResolvedValue([]);
    const m = new AppModel(api);
    const first = m.toggleLayer("points", true);
    m.access = session;
    m.setAddMode(true);
    m.addDraft(52, 35);
    m.setDistanceMode(true);
    m.clickPoint(point);
    const snapshot = JSON.parse(JSON.stringify(m.selected[0]));
    m.setViewport({ bbox: [30, 50, 40, 60], zoom: 10 });
    expect(get.mock.calls[0]?.[1]?.aborted).toBe(true);
    m.setViewport({ bbox: [40, 50, 50, 60], zoom: 11 });
    await vi.advanceTimersByTimeAsync(150);
    expect(get).toHaveBeenCalledTimes(2);
    expect(get.mock.calls[1]?.[2]).toEqual({
      bbox: [40, 50, 50, 60],
      zoom: 11,
    });
    old.resolve([point]);
    await first;
    expect(m.layers.points.data).toEqual([]);
    expect(m.viewport).toEqual({ bbox: [40, 50, 50, 60], zoom: 11 });
    expect(m.points).toEqual([]);
    expect(m.selected[0]).toEqual(snapshot);
    expect(m.drafts).toHaveLength(1);
    m.destroy();
    vi.useRealTimers();
  });
  it("filters saved overlays by a wrapped viewport and cancels pending refresh on destroy", async () => {
    vi.useFakeTimers();
    const api = new Api();
    const get = vi.spyOn(api, "layer").mockResolvedValue([]);
    vi.spyOn(api, "session").mockResolvedValue(session);
    vi.spyOn(api, "save").mockResolvedValue([point]);
    const m = new AppModel(api);
    await m.refreshSession();
    await m.toggleLayer("points", true);
    m.setAddMode(true);
    m.addDraft(52, 35);
    await m.save();
    expect(m.points).toHaveLength(1);
    m.setViewport({ bbox: [170, -70, -170, 70], zoom: 4 });
    expect(m.points).toEqual([]);
    m.destroy();
    await vi.advanceTimersByTimeAsync(1000);
    expect(get).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
  it("surfaces a whole-layer limit error and retries the current viewport", async () => {
    const api = new Api();
    const get = vi
      .spyOn(api, "layer")
      .mockRejectedValueOnce(new ApiError(413, "Приблизьте карту."))
      .mockResolvedValue([]);
    const m = new AppModel(api);
    await m.toggleLayer("roads", true);
    expect(m.layers.roads.error).toBe("Приблизьте карту.");
    expect(m.layers.roads.data).toEqual([]);
    await m.toggleLayer("roads", true);
    expect(get).toHaveBeenCalledTimes(2);
    expect(m.layers.roads.error).toBe("");
    m.destroy();
  });
});
