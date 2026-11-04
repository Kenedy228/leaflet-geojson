import type { Geometry } from "geojson";
export type Coordinate = [number, number];
// WGS84 west, south, east, north; west > east crosses the dateline.
export type BBox = [number, number, number, number];
export interface Viewport {
  bbox: BBox;
  zoom: number;
}
export type LayerName = "points" | "roads" | "polygons";
export interface GeoItem {
  id: string;
  name: string;
  source?: "osm" | "user";
  geojson: Geometry;
}
export interface Draft {
  id: string;
  name: string;
  lat: number;
  lng: number;
}
export interface Session {
  editing_enabled: boolean;
  can_edit: boolean;
  csrf_header: string;
  csrf_token: string;
}
export interface DistanceResult {
  lines: { from: Coordinate; to: Coordinate; distance: number }[];
  totalDistance: number;
}
