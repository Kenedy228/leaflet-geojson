CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;

-- Keep osm_id non-unique: old user points can have collisions, and OSM ways/relations
-- can produce multiple geometries. User identity lives separately in app.user_points.
CREATE TABLE IF NOT EXISTS public.planet_osm_point (
    osm_id bigint NOT NULL,
    name text,
    tags public.hstore,
    way public.geometry(Point, 3857) NOT NULL
);

CREATE TABLE IF NOT EXISTS public.planet_osm_roads (
    osm_id bigint NOT NULL,
    name text,
    highway text,
    tags public.hstore,
    way public.geometry(Geometry, 3857) NOT NULL,
    CHECK (public.GeometryType(way) IN ('LINESTRING', 'MULTILINESTRING'))
);

CREATE TABLE IF NOT EXISTS public.planet_osm_polygon (
    osm_id bigint NOT NULL,
    name text,
    tags public.hstore,
    way public.geometry(Geometry, 3857) NOT NULL,
    CHECK (public.GeometryType(way) IN ('POLYGON', 'MULTIPOLYGON'))
);

-- Older prepared databases may not have hstore on points or roads. Never replace
-- existing columns or transform someone else's geometries implicitly.
ALTER TABLE public.planet_osm_point ADD COLUMN IF NOT EXISTS tags public.hstore;
ALTER TABLE public.planet_osm_roads ADD COLUMN IF NOT EXISTS tags public.hstore;
ALTER TABLE public.planet_osm_polygon ADD COLUMN IF NOT EXISTS tags public.hstore;

DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['planet_osm_point', 'planet_osm_roads', 'planet_osm_polygon']
    LOOP
        IF public.Find_SRID('public', target, 'way') <> 3857 THEN
            RAISE EXCEPTION 'Expected public.%.way in EPSG:3857; review the import before migration', target;
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = target AND column_name = 'tags'
              AND udt_schema = 'public' AND udt_name = 'hstore'
        ) THEN
            RAISE EXCEPTION 'Expected public.%.tags to use hstore; existing column was not changed', target;
        END IF;
    END LOOP;
END;
$$;

CREATE INDEX IF NOT EXISTS planet_osm_point_way_idx ON public.planet_osm_point USING gist (way);
CREATE INDEX IF NOT EXISTS planet_osm_roads_way_idx ON public.planet_osm_roads USING gist (way);
CREATE INDEX IF NOT EXISTS planet_osm_polygon_way_idx ON public.planet_osm_polygon USING gist (way);
CREATE INDEX IF NOT EXISTS planet_osm_point_osm_id_idx ON public.planet_osm_point (osm_id);
