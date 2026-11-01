-- Data values: (c) OpenStreetMap contributors, ODbL 1.0.
-- License: https://opendatacommons.org/licenses/odbl/1-0/
-- Source: https://api.openstreetmap.org/api/0.6/map?bbox=35.34,52.33,35.36,52.35
-- Retrieved: 2026-11-01T01:53:21+00:00
-- Raw response SHA-256: c9c1c693f940db59aa05a71abe15d7ba96e7123093d0c774ac1725da4d3d644c
-- Curated subset: 3 nodes, 2 highway ways, 2 closed leisure/landuse ways.
-- Editor usernames, user IDs and changeset metadata were not retained.
-- See infra/README.md. SQL application code does not change the data license.

DO $$
DECLARE
    marker constant text := 'osm-zheleznogorsk-2026-11-01';
BEGIN
    -- Keep this migration resolvable in every profile. A missing applied repeatable
    -- would fail validation when the same volume switches from demo to normal.
    IF NOT ${demo_data_enabled} THEN
        RETURN;
    END IF;
    LOCK TABLE public.planet_osm_point, public.planet_osm_roads, public.planet_osm_polygon
        IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM public.planet_osm_point WHERE tags->'leaflet:demo' IS DISTINCT FROM marker)
       OR EXISTS (SELECT 1 FROM public.planet_osm_roads WHERE tags->'leaflet:demo' IS DISTINCT FROM marker)
       OR EXISTS (SELECT 1 FROM public.planet_osm_polygon WHERE tags->'leaflet:demo' IS DISTINCT FROM marker)
    THEN
        RAISE EXCEPTION 'Demo seed requires empty or demo-only reference tables; existing imports were not changed';
    END IF;

    -- Reset only this snapshot, atomically; app.user_points is never touched.
    DELETE FROM public.planet_osm_point WHERE tags->'leaflet:demo' = marker;
    DELETE FROM public.planet_osm_roads WHERE tags->'leaflet:demo' = marker;
    DELETE FROM public.planet_osm_polygon WHERE tags->'leaflet:demo' = marker;

    -- node/2469931302 version 15, last edited 2024-05-16T20:40:34Z
    INSERT INTO public.planet_osm_point (osm_id, name, tags, way)
    VALUES (2469931302, 'ВТБ', public.hstore(ARRAY['amenity','name'],ARRAY['bank','ВТБ']) || public.hstore('leaflet:demo', marker), public.ST_Transform(public.ST_SetSRID(public.ST_GeomFromGeoJSON('{"type":"Point","coordinates":[35.3537589,52.3392233]}'),4326),3857));

    -- node/981410169 version 21, last edited 2024-06-04T20:10:09Z
    INSERT INTO public.planet_osm_point (osm_id, name, tags, way)
    VALUES (981410169, 'Сбербанк', public.hstore(ARRAY['amenity','name'],ARRAY['bank','Сбербанк']) || public.hstore('leaflet:demo', marker), public.ST_Transform(public.ST_SetSRID(public.ST_GeomFromGeoJSON('{"type":"Point","coordinates":[35.3525012,52.3407592]}'),4326),3857));

    -- node/1480279943 version 14, last edited 2023-08-26T21:39:46Z
    INSERT INTO public.planet_osm_point (osm_id, name, tags, way)
    VALUES (1480279943, 'Аптека', public.hstore(ARRAY['amenity','name'],ARRAY['pharmacy','Аптека']) || public.hstore('leaflet:demo', marker), public.ST_Transform(public.ST_SetSRID(public.ST_GeomFromGeoJSON('{"type":"Point","coordinates":[35.3508088,52.337335]}'),4326),3857));

    -- way/228935059 version 25, last edited 2023-08-26T21:44:01Z
    INSERT INTO public.planet_osm_roads (osm_id, name, highway, tags, way)
    VALUES (228935059, 'улица Ленина', 'secondary', public.hstore(ARRAY['highway','name'],ARRAY['secondary','улица Ленина']) || public.hstore('leaflet:demo', marker), public.ST_Transform(public.ST_SetSRID(public.ST_GeomFromGeoJSON('{"type":"LineString","coordinates":[[35.360143,52.3281539],[35.360077,52.3282326],[35.3593568,52.3290472],[35.359261,52.3291557],[35.3590803,52.3293632],[35.3586373,52.3298696],[35.3583475,52.3301966],[35.3581888,52.3303735],[35.3581391,52.3304349],[35.3569439,52.3317893],[35.3563667,52.3324433],[35.3563016,52.3325137],[35.3562443,52.3325805],[35.3554965,52.333414],[35.3551952,52.3337655],[35.3545227,52.3345348],[35.354414,52.3346582],[35.3536314,52.335547],[35.3533167,52.3358937],[35.3533013,52.3359123],[35.3531029,52.3361475],[35.3530647,52.3361861],[35.3527383,52.3364282],[35.3519458,52.3373307],[35.3516185,52.337726],[35.3514875,52.3378597],[35.351019,52.338454],[35.3508209,52.3387002],[35.3507004,52.338855],[35.3506201,52.3389494],[35.3505581,52.3390219],[35.349759,52.3399561],[35.3493886,52.3403791],[35.3491374,52.3406708],[35.3490369,52.3408044],[35.3489471,52.3409342],[35.3488773,52.3410508],[35.3488302,52.3411366],[35.3487734,52.3412384],[35.3486752,52.3414468],[35.3485861,52.3416563],[35.3484225,52.3420817],[35.3483241,52.342364],[35.3480287,52.3431139],[35.3477831,52.3437635],[35.3477732,52.3437974],[35.3476484,52.3441173],[35.34757,52.3443253],[35.34746,52.344613],[35.3474069,52.3447355],[35.3473093,52.3450253],[35.3472574,52.3451639],[35.3471373,52.345463],[35.3470483,52.345691],[35.3469697,52.3458682],[35.3468236,52.3462661],[35.3465594,52.3469436],[35.3463702,52.3474397],[35.3463052,52.3476232],[35.3462848,52.3476758],[35.3462431,52.3477934],[35.3461544,52.3480172],[35.3458968,52.3486954],[35.3457111,52.3491917],[35.3455282,52.3496735],[35.3454636,52.3498363]]}'),4326),3857));

    -- way/138714967 version 21, last edited 2023-08-27T05:57:37Z
    INSERT INTO public.planet_osm_roads (osm_id, name, highway, tags, way)
    VALUES (138714967, 'улица Гайдара', 'residential', public.hstore(ARRAY['highway','name'],ARRAY['residential','улица Гайдара']) || public.hstore('leaflet:demo', marker), public.ST_Transform(public.ST_SetSRID(public.ST_GeomFromGeoJSON('{"type":"LineString","coordinates":[[35.3477717,52.3409873],[35.348272,52.3410589],[35.3488302,52.3411366],[35.3491209,52.3411717],[35.3500141,52.3412931],[35.3508965,52.341413],[35.3514194,52.3414931],[35.3515685,52.341516],[35.3520187,52.3415905],[35.3524846,52.3416517],[35.3526998,52.3416799],[35.3535755,52.341811],[35.3549342,52.3419923],[35.3551326,52.3420187]]}'),4326),3857));

    -- way/285882416 version 6, last edited 2023-08-27T05:57:37Z
    INSERT INTO public.planet_osm_polygon (osm_id, name, tags, way)
    VALUES (285882416, '8 микрорайон', public.hstore(ARRAY['landuse','name'],ARRAY['residential','8 микрорайон']) || public.hstore('leaflet:demo', marker), public.ST_Transform(public.ST_SetSRID(public.ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[35.3547264,52.3392722],[35.3552485,52.3394708],[35.3554176,52.3395397],[35.356222,52.3398619],[35.356345,52.3399111],[35.3563966,52.3399318],[35.3564167,52.3399687],[35.3563698,52.3400315],[35.3563449,52.3400603],[35.3557283,52.3407717],[35.355536,52.3410408],[35.3553371,52.3413726],[35.3551695,52.3417427],[35.3551225,52.3418424],[35.3550264,52.3418957],[35.3549191,52.3419326],[35.3547872,52.3419408],[35.3545995,52.3419189],[35.3536986,52.341796],[35.3517294,52.3415215],[35.3516779,52.3415078],[35.3516489,52.3414559],[35.3516712,52.3413904],[35.3534393,52.3391138],[35.3536428,52.3388734],[35.3537121,52.3388504],[35.3538618,52.3389128],[35.3547264,52.3392722]]]}'),4326),3857));

    -- way/62223168 version 15, last edited 2023-08-27T00:20:53Z
    INSERT INTO public.planet_osm_polygon (osm_id, name, tags, way)
    VALUES (62223168, 'Спорт Клуб «ГОТЭК»', public.hstore(ARRAY['leisure','name'],ARRAY['sports_centre','Спорт Клуб «ГОТЭК»']) || public.hstore('leaflet:demo', marker), public.ST_Transform(public.ST_SetSRID(public.ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[35.3547533,52.3407146],[35.355077,52.3408282],[35.3551414,52.3407579],[35.3552423,52.3406525],[35.3553681,52.3405189],[35.3550444,52.3404052],[35.3547533,52.3407146]]]}'),4326),3857));
END;
$$;
