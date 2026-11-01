package com.github.gis.points;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PointService {
    private static final RowMapper<Point> POINT = (row, index) -> new Point(
            row.getString("id"), row.getString("source"), row.getString("osm_id"),
            row.getString("name"), row.getString("geojson"));

    private final JdbcTemplate jdbc;

    public PointService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Point> list() {
        return jdbc.query("""
                SELECT 'osm:' || osm_id::text AS id, 'osm' AS source,
                       osm_id::text AS osm_id, name,
                       ST_AsGeoJSON(ST_Transform(way, 4326)) AS geojson
                FROM public.planet_osm_point WHERE name IS NOT NULL
                UNION ALL
                SELECT 'user:' || id::text, 'user', NULL::text, name,
                       ST_AsGeoJSON(geom)
                FROM app.user_points
                ORDER BY id
                """, POINT);
    }

    @Transactional
    public List<Point> create(List<NewPoint> points) {
        if (points == null || points.isEmpty() || points.size() > 1000) {
            throw badRequest("Supply between 1 and 1000 points");
        }
        for (NewPoint point : points) {
            if (point == null || point.name() == null || point.name().isBlank()
                    || point.name().length() > 255 || point.lat() == null || point.lng() == null
                    || !Double.isFinite(point.lat()) || !Double.isFinite(point.lng())
                    || point.lat() < -90 || point.lat() > 90 || point.lng() < -180 || point.lng() > 180) {
                throw badRequest("Each point needs a name (1–255 characters) and valid lat/lng");
            }
        }

        var created = new ArrayList<Point>();
        for (NewPoint point : points) {
            created.add(jdbc.queryForObject("""
                    INSERT INTO app.user_points(name, geom)
                    VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                    RETURNING 'user:' || id::text AS id, 'user' AS source,
                              NULL::text AS osm_id, name, ST_AsGeoJSON(geom) AS geojson
                    """, POINT, point.name(), point.lng(), point.lat()));
        }
        return created;
    }

    public void delete(String id) {
        if (id.startsWith("osm:")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Imported OSM points are read-only");
        }
        if (!id.startsWith("user:")) {
            throw badRequest("Use the full user:<uuid> ID returned by the API");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(id.substring(5));
        } catch (IllegalArgumentException exception) {
            throw badRequest("Invalid user point ID");
        }
        if (jdbc.update("DELETE FROM app.user_points WHERE id = ?", uuid) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User point not found");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
