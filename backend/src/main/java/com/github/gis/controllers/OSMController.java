package com.github.gis.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.gis.points.NewPoint;
import com.github.gis.points.Point;
import com.github.gis.points.PointService;
import com.github.gis.geo.DistanceRequest;
import com.github.gis.geo.DistanceResult;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/osm")
public class OSMController {

    private final JdbcTemplate jdbcTemplate;
    private final PointService points;

    public OSMController(JdbcTemplate jdbcTemplate, PointService points) {
        this.jdbcTemplate = jdbcTemplate;
        this.points = points;
    }

    @GetMapping("/points")
    public List<Point> getAllPoints() {
        return points.list();
    }

    @DeleteMapping("/points/{id}")
    public ResponseEntity<Void> deletePoint(@PathVariable String id) {
        points.delete(id);
        return ResponseEntity.noContent().build();
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
    public ResponseEntity<CreatedPoints> addPoints(@RequestBody List<NewPoint> input) {
        List<Point> created = points.create(input);
        return ResponseEntity.status(201).body(new CreatedPoints(created.size(), created));
    }

    public record CreatedPoints(int inserted, List<Point> points) {
    }

    @PostMapping("/distances")
    public DistanceResult getDistance(@RequestBody DistanceRequest body) {
        body.validate();
        List<List<Double>> points = body.points();
        var lines = new ArrayList<DistanceResult.Segment>();
        double totalDistance = 0;

        for (int i = 0; i < points.size() - 1; i++) {
            List<Double> p1 = points.get(i);
            List<Double> p2 = points.get(i + 1);

            String sql = "SELECT ST_Distance(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, " +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)";

            Double distance = jdbcTemplate.queryForObject(sql, Double.class, p1.get(0), p1.get(1), p2.get(0), p2.get(1));

            lines.add(new DistanceResult.Segment(p1, p2, distance));
            totalDistance += distance;
        }

        return new DistanceResult(lines, totalDistance);
    }
}
