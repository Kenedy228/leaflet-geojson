CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

CREATE TABLE app.user_points (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 255),
    geom public.geometry(Point, 4326) NOT NULL,
    legacy_osm_id bigint,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (NOT public.ST_IsEmpty(geom)),
    CHECK (public.ST_X(geom) BETWEEN -180 AND 180),
    CHECK (public.ST_Y(geom) BETWEEN -90 AND 90)
);

CREATE INDEX user_points_geom_idx ON app.user_points USING gist (geom);
