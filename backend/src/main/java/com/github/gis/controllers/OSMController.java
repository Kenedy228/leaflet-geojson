package com.github.gis.controllers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.websocket.server.PathParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = {"http://127.0.0.1:5500", "http://localhost:5500"})
@RestController
@RequestMapping("/api/v1/osm")
public class OSMController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/points")
    public List<Map<String, Object>> getAllPoints() {
        String sql = """
                SELECT name,
                osm_id,
                ST_AsGeoJSON(ST_Transform(way, 4326)) AS geojson
                FROM planet_osm_point
                WHERE name IS NOT NULL;
                """;
        return jdbcTemplate.queryForList(sql);
    }

    @DeleteMapping("/points/{id}")
    public void deletePoint(@PathVariable long id) {

        String sql = """
                DELETE
                FROM planet_osm_point
                WHERE osm_id = ?;
                """;

        jdbcTemplate.update(sql, id);
    }

    @GetMapping("/roads")
    public List<Map<String, Object>> getAllRoads() {
        String sql = """
                  SELECT
                  osm_id,
                  name,
                  highway,
                  ST_AsGeoJSON(ST_Transform(way, 4326)) AS geojson
                FROM planet_osm_roads
                WHERE highway IS NOT NULL;
                """;
        return jdbcTemplate.queryForList(sql);
    }

    @GetMapping("/polygons")
    public List<Map<String, Object>> getAllPolygons() {
        String sql = """
                SELECT
                  osm_id,
                  name,
                  tags->'leisure' AS leisure_type,
                  tags->'landuse' AS landuse_type,
                  ST_AsGeoJSON(ST_Transform(way, 4326)) AS geojson
                FROM planet_osm_polygon
                WHERE name IS NOT NULL;
                """;

        return jdbcTemplate.queryForList(sql);
    }

    @PostMapping("/points/add")
    public ResponseEntity<?> addPoints(@RequestBody List<Map<String, Object>> points) {

        String sql = """
        INSERT INTO planet_osm_point(osm_id, name, way)
        VALUES (?, ?, ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 3857))
        """;

        int insertedCount = 0;

        for (Map<String, Object> point : points) {
            try {
                String name = (String) point.get("name");

                if (point.get("lat") == null || point.get("lng") == null) {
                    System.out.println("Пропущена точка: lat или lng = null -> " + point);
                    continue;
                }

                double lat = ((Number) point.get("lat")).doubleValue();
                double lng = ((Number) point.get("lng")).doubleValue();

                long osmId = -System.currentTimeMillis(); // уникальный отрицательный ID

                jdbcTemplate.update(sql, osmId, name, lng, lat);
                insertedCount++;

            } catch (Exception ex) {
                System.out.println("Ошибка при вставке точки: " + point + " -> " + ex.getMessage());
                ex.printStackTrace();
            }
        }

        System.out.println("Всего успешно вставлено точек: " + insertedCount);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "inserted", insertedCount
        ));
    }

    @PostMapping("/distances")
    public Map<String, Object> getDistance(@RequestBody Map<String, List<List<Double>>> body) {
        List<List<Double>> points = body.get("points");
        List<Map<String, Object>> lines = new ArrayList<>();
        double totalDistance = 0;

        for (int i = 0; i < points.size() - 1; i++) {
            List<Double> p1 = points.get(i);
            List<Double> p2 = points.get(i + 1);

            String sql = "SELECT ST_Distance(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, " +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)";

            Double distance = jdbcTemplate.queryForObject(sql, Double.class, p1.get(0), p1.get(1), p2.get(0), p2.get(1));

            Map<String, Object> line = new HashMap<>();
            line.put("from", p1);
            line.put("to", p2);
            line.put("distance", distance);

            lines.add(line);
            totalDistance += distance;
        }

        return Map.of("lines", lines, "totalDistance", totalDistance);
    }
}
