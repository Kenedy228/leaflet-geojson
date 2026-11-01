import { afterEach, describe, expect, it, vi } from "vitest";
import { Api } from "../api";
const session = {
  editing_enabled: true,
  can_edit: true,
  csrf_header: "X-CSRF-TOKEN",
  csrf_token: "fresh",
};
const draft = { id: "draft:1", name: "User name", lat: 52, lng: 35 };
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
describe("API transport and acknowledgement", () => {
  it("accepts successful logout redirect HTML without decoding JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("<!doctype html><title>Home</title>", {
          headers: { "Content-Type": "text/html" },
        }),
      ),
    );
    await expect(new Api().logout(session)).resolves.toBeUndefined();
  });
  it("preserves opaque OSM ids above Number precision and decodes real GeoJSON strings", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify([
          {
            id: "osm:9007199254740993",
            source: "osm",
            name: "A",
            geojson: '{"type":"Point","coordinates":[35,52]}',
          },
        ]),
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    expect((await new Api().layer("points"))[0]?.id).toBe(
      "osm:9007199254740993",
    );
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/v1/osm/points");
  });
  it("rejects malformed successful saves, so callers retain drafts", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            inserted: 1,
            points: [
              {
                id: "user:00000000-0000-0000-0000-000000000001",
                source: "user",
                name: "A",
                geojson: { type: "Point", coordinates: [999, 52] },
              },
            ],
          }),
        ),
      ),
    );
    await expect(new Api().save([draft], session)).rejects.toThrow(
      "Сервер не подтвердил",
    );
  });
  it.each([{ inserted: 0 }, null, { inserted: 1, points: [] }])(
    "warns about uncertain saves for malformed successful acknowledgement %j",
    async (body) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(new Response(JSON.stringify(body))),
      );
      await expect(new Api().save([draft], session)).rejects.toThrow(
        "Сервер не подтвердил",
      );
    },
  );
  it("warns about uncertain saves after malformed successful JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("invalid json")),
    );
    await expect(new Api().save([draft], session)).rejects.toThrow(
      "Сервер не подтвердил",
    );
  });
  it("sends only request fields with the refreshed CSRF header", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          inserted: 1,
          points: [
            {
              id: "user:00000000-0000-0000-0000-000000000001",
              source: "user",
              name: "Canonical",
              geojson: { type: "Point", coordinates: [35, 52] },
            },
          ],
        }),
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    expect((await new Api().save([draft], session))[0]?.name).toBe("Canonical");
    const options = fetcher.mock.calls[0]?.[1] as RequestInit;
    expect(options.credentials).toBe("same-origin");
    expect(options.headers).toMatchObject({ "X-CSRF-TOKEN": "fresh" });
    expect(JSON.parse(String(options.body))).toEqual([
      { name: "User name", lat: 52, lng: 35 },
    ]);
  });
  it("shows public HTTP errors without exposing raw error bodies", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response("private database details", { status: 500 }),
        ),
    );
    await expect(new Api().layer("points")).rejects.toThrow("HTTP 500");
  });
  it("distinguishes timeout from deliberate cancellation", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_path: string, options: RequestInit) =>
          new Promise((_resolve, reject) =>
            options.signal?.addEventListener("abort", () =>
              reject(new DOMException("Aborted", "AbortError")),
            ),
          ),
      ),
    );
    const request = new Api().layer("points");
    const assertion = expect(request).rejects.toThrow("Время ожидания");
    await vi.advanceTimersByTimeAsync(20000);
    await assertion;
    const controller = new AbortController();
    const cancelled = new Api().layer("points", controller.signal);
    const cancellation = expect(cancelled).rejects.toMatchObject({
      name: "AbortError",
    });
    controller.abort();
    await cancellation;
  });
});
