import type { Geometry } from "geojson";
import type {
  Coordinate,
  DistanceResult,
  Draft,
  GeoItem,
  LayerName,
  Session,
} from "./types";
const root = "/api/v1/osm";
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}
class InvalidResponseError extends Error {}
const invalid = () =>
  new InvalidResponseError(
    "Сервер вернул некорректные данные. Повторите запрос.",
  );
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw invalid();
  return value as Record<string, unknown>;
}
export function coordinate(value: unknown): Coordinate {
  if (
    !Array.isArray(value) ||
    value.length !== 2 ||
    typeof value[0] !== "number" ||
    typeof value[1] !== "number" ||
    !Number.isFinite(value[0]) ||
    !Number.isFinite(value[1]) ||
    Math.abs(value[0]) > 180 ||
    Math.abs(value[1]) > 90
  )
    throw invalid();
  return [value[0], value[1]];
}
function line(value: unknown) {
  if (!Array.isArray(value) || value.length < 2) throw invalid();
  value.forEach(coordinate);
}
function ring(value: unknown) {
  line(value);
  const points = value as unknown[];
  if (
    points.length < 4 ||
    JSON.stringify(points[0]) !== JSON.stringify(points.at(-1))
  )
    throw invalid();
}
function polygon(value: unknown) {
  if (!Array.isArray(value) || value.length === 0) throw invalid();
  value.forEach(ring);
}
function geometry(value: unknown, layer: LayerName): Geometry {
  const g = object(typeof value === "string" ? JSON.parse(value) : value);
  const c = g.coordinates;
  if (layer === "points" && g.type === "Point") coordinate(c);
  else if (layer === "roads" && g.type === "LineString") line(c);
  else if (
    layer === "roads" &&
    g.type === "MultiLineString" &&
    Array.isArray(c) &&
    c.length
  )
    c.forEach(line);
  else if (layer === "polygons" && g.type === "Polygon") polygon(c);
  else if (
    layer === "polygons" &&
    g.type === "MultiPolygon" &&
    Array.isArray(c) &&
    c.length
  )
    c.forEach(polygon);
  else throw invalid();
  return g as unknown as Geometry;
}
export function decodeItems(value: unknown, layer: LayerName): GeoItem[] {
  if (!Array.isArray(value)) throw invalid();
  const ids = new Set<string>();
  return value.map((raw, index) => {
    const item = object(raw);
    if (item.name !== null && typeof item.name !== "string") throw invalid();
    let id = `${layer}:${index}`;
    let source: GeoItem["source"];
    if (layer === "points") {
      if (
        typeof item.id !== "string" ||
        !/^(osm:-?\d+|user:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i.test(
          item.id,
        ) ||
        (item.source !== "osm" && item.source !== "user") ||
        !item.id.startsWith(`${item.source}:`)
      )
        throw invalid();
      id = item.id;
      source = item.source;
    }
    if (ids.has(id)) throw invalid();
    ids.add(id);
    return {
      id,
      name: typeof item.name === "string" ? item.name : "Без названия",
      source,
      geojson: geometry(item.geojson, layer),
    };
  });
}
export function decodeDistance(
  value: unknown,
  points: Coordinate[],
): DistanceResult {
  const data = object(value);
  if (
    !Array.isArray(data.lines) ||
    data.lines.length !== points.length - 1 ||
    typeof data.totalDistance !== "number" ||
    !Number.isFinite(data.totalDistance) ||
    data.totalDistance < 0
  )
    throw invalid();
  const lines = data.lines.map((raw, index) => {
    const segment = object(raw);
    const from = coordinate(segment.from),
      to = coordinate(segment.to);
    if (
      JSON.stringify(from) !== JSON.stringify(points[index]) ||
      JSON.stringify(to) !== JSON.stringify(points[index + 1]) ||
      typeof segment.distance !== "number" ||
      !Number.isFinite(segment.distance) ||
      segment.distance < 0
    )
      throw invalid();
    return { from, to, distance: segment.distance };
  });
  const total = lines.reduce((sum, item) => sum + item.distance, 0);
  if (Math.abs(total - data.totalDistance) > Math.max(0.01, total * 1e-9))
    throw invalid();
  return { lines, totalDistance: data.totalDistance };
}
export class Api {
  async request(
    path: string,
    options: RequestInit = {},
    signal?: AbortSignal,
    noBody = false,
  ): Promise<unknown> {
    const controller = new AbortController();
    const cancel = () => controller.abort();
    signal?.addEventListener("abort", cancel, { once: true });
    if (signal?.aborted) controller.abort();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, 20000);
    try {
      const response = await fetch(path, {
        ...options,
        credentials: "same-origin",
        cache: "no-store",
        signal: controller.signal,
      });
      if (!response.ok) {
        let message = `Ошибка запроса (HTTP ${response.status}). Повторите попытку.`;
        try {
          const error = object(await response.json());
          if (
            typeof error.code === "string" &&
            typeof error.message === "string"
          )
            message = error.message;
        } catch {
          /* use safe fallback */
        }
        throw new ApiError(response.status, message);
      }
      if (response.status === 204 || noBody) return undefined;
      return await response.json();
    } catch (error) {
      if (timedOut)
        throw new Error("Время ожидания истекло. Повторите запрос.", {
          cause: error,
        });
      if (signal?.aborted) throw error;
      if (error instanceof TypeError)
        throw new Error(
          "Сеть недоступна. Проверьте подключение и повторите запрос.",
          { cause: error },
        );
      if (error instanceof SyntaxError) throw invalid();
      throw error;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", cancel);
    }
  }
  async session(signal?: AbortSignal): Promise<Session> {
    const s = object(await this.request("/api/v1/session", {}, signal));
    if (
      typeof s.editing_enabled !== "boolean" ||
      typeof s.can_edit !== "boolean" ||
      typeof s.csrf_header !== "string" ||
      !/^[-A-Za-z0-9]+$/.test(s.csrf_header) ||
      typeof s.csrf_token !== "string" ||
      !s.csrf_token
    )
      throw invalid();
    return s as unknown as Session;
  }
  async layer(name: LayerName, signal?: AbortSignal) {
    return decodeItems(await this.request(`${root}/${name}`, {}, signal), name);
  }
  async save(drafts: Draft[], session: Session, signal?: AbortSignal) {
    try {
      const result = object(
        await this.request(
          `${root}/points/add`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              [session.csrf_header]: session.csrf_token,
            },
            body: JSON.stringify(
              drafts.map(({ name, lat, lng }) => ({ name, lat, lng })),
            ),
          },
          signal,
        ),
      );
      const points = decodeItems(result.points, "points");
      if (
        result.inserted !== drafts.length ||
        points.length !== drafts.length ||
        points.some((p) => p.source !== "user")
      )
        throw invalid();
      return points;
    } catch (error) {
      if (error instanceof InvalidResponseError || error instanceof SyntaxError)
        throw new Error(
          "Сервер не подтвердил созданные точки. Черновики сохранены; проверьте точки перед повторной отправкой.",
          { cause: error },
        );
      throw error;
    }
  }

  async remove(id: string, session: Session, signal?: AbortSignal) {
    await this.request(
      `${root}/points/${encodeURIComponent(id)}`,
      {
        method: "DELETE",
        headers: { [session.csrf_header]: session.csrf_token },
      },
      signal,
    );
  }
  async distance(points: Coordinate[], signal?: AbortSignal) {
    return decodeDistance(
      await this.request(
        `${root}/distances`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ points }),
        },
        signal,
      ),
      points,
    );
  }
  async logout(session: Session, signal?: AbortSignal) {
    await this.request(
      "/logout",
      {
        method: "POST",
        headers: { [session.csrf_header]: session.csrf_token },
      },
      signal,
      true,
    );
  }
}
