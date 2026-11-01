-- Installation does not move any rows. An operator must review and supply IDs.
-- SECURITY INVOKER: no elevated privileges are granted to the caller.
CREATE FUNCTION app.migrate_legacy_user_points(legacy_ids bigint[])
RETURNS integer
LANGUAGE plpgsql
SET search_path = pg_catalog, public, app
AS $$
DECLARE
    moved_count integer;
BEGIN
    IF legacy_ids IS NULL OR cardinality(legacy_ids) = 0 OR EXISTS (
        SELECT 1 FROM unnest(legacy_ids) AS ids(id) WHERE id IS NULL OR id >= 0
    ) THEN
        RAISE EXCEPTION 'Supply a reviewed, nonempty list of negative legacy IDs'
            USING ERRCODE = '22023';
    END IF;

    -- DELETE RETURNING preserves every row, including collisions of osm_id.
    -- If an INSERT fails, the entire statement and its DELETE are rolled back.
    WITH moved AS (
        DELETE FROM public.planet_osm_point
        WHERE osm_id = ANY(legacy_ids)
        RETURNING osm_id, name, way
    )
    INSERT INTO app.user_points (legacy_osm_id, name, geom)
    SELECT osm_id, name, public.ST_Transform(way, 4326) FROM moved;

    GET DIAGNOSTICS moved_count = ROW_COUNT;
    RETURN moved_count;
END;
$$;
