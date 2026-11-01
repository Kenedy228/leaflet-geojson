package com.github.gis.points;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.github.gis.api.ApiException;
import com.github.gis.geo.Coordinates;

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
            throw invalidBatch();
        }
        for (NewPoint point : points) {
            if (point == null || !validName(point.name()) || !Coordinates.valid(point.lng(), point.lat())) {
                throw invalidBatch();
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
            throw new ApiException(HttpStatus.FORBIDDEN, "read_only_point", "Импортированные точки доступны только для чтения.");
        }
        if (!id.startsWith("user:")) {
            throw invalidId();
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(id.substring(5));
            if (!uuid.toString().equalsIgnoreCase(id.substring(5))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw invalidId();
        }
        if (jdbc.update("DELETE FROM app.user_points WHERE id = ?", uuid) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "point_not_found", "Пользовательская точка не найдена.");
        }
    }

    private ApiException invalidBatch() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_point_batch",
                "Требуется 1–1000 точек с непустыми названиями до 255 символов корректного Unicode (без NUL) и конечными координатами lng от −180 до 180, lat от −90 до 90.");
    }

    private boolean validName(String name) {
        return name != null && !name.isBlank() && name.codePointCount(0, name.length()) <= 255
                && name.codePoints().noneMatch(c -> c == 0 || c >= 0xD800 && c <= 0xDFFF);
    }

    private ApiException invalidId() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_point_id", "Используйте полный ID user:<uuid>, возвращённый API.");
    }
}
