package com.github.gis.controllers;

import com.github.gis.geo.DistanceRequest;
import com.github.gis.geo.DistanceResult;
import com.github.gis.geo.SpatialQuery;
import com.github.gis.geo.SpatialService;
import com.github.gis.points.NewPoint;
import com.github.gis.points.Point;
import com.github.gis.points.PointService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/osm")
public class OSMController {

  private final SpatialService spatial;
  private final PointService points;

  public OSMController(SpatialService spatial, PointService points) {
    this.spatial = spatial;
    this.points = points;
  }

  @GetMapping("/points")
  public List<Point> getAllPoints(@RequestParam MultiValueMap<String, String> query) {
    return spatial.points(SpatialQuery.parse(query));
  }

  @DeleteMapping("/points/{id}")
  public ResponseEntity<Void> deletePoint(@PathVariable String id) {
    points.delete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/roads")
  public List<Map<String, Object>> getAllRoads(@RequestParam MultiValueMap<String, String> query) {
    return spatial.layer("roads", SpatialQuery.parse(query));
  }

  @GetMapping("/polygons")
  public List<Map<String, Object>> getAllPolygons(
      @RequestParam MultiValueMap<String, String> query) {
    return spatial.layer("polygons", SpatialQuery.parse(query));
  }

  @PostMapping("/points/add")
  public ResponseEntity<CreatedPoints> addPoints(@RequestBody List<NewPoint> input) {
    List<Point> created = points.create(input);
    return ResponseEntity.status(201).body(new CreatedPoints(created.size(), created));
  }

  public record CreatedPoints(int inserted, List<Point> points) {}

  @PostMapping("/distances")
  public DistanceResult getDistance(@RequestBody DistanceRequest body) {
    return spatial.distance(body);
  }
}
