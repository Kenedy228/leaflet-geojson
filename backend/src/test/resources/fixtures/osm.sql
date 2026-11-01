-- Test-only contract of the legacy OSM tables. Apply to a new, isolated database.
-- Intentionally allows duplicate legacy osm_id values to reproduce old collisions.
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;

CREATE TABLE public.planet_osm_point (
    osm_id bigint,
    name text,
    way public.geometry(Point, 3857)
);

CREATE TABLE public.planet_osm_roads (
    osm_id bigint,
    name text,
    highway text,
    way public.geometry(LineString, 3857)
);

CREATE TABLE public.planet_osm_polygon (
    osm_id bigint,
    name text,
    tags public.hstore,
    way public.geometry(Polygon, 3857)
);
