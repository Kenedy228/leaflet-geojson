package com.github.gis.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit opt-in via make benchmark-spatial, not a default Surefire *Tests class. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"EDITING_ENABLED=false", "CORS_ALLOWED_ORIGINS="})
class SpatialQueryBenchmark {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired TestRestTemplate client;
  private static final int SAMPLES = 20;

  @Test
  void measureActualProductionSqlAndHttpOnOwnedDataset() throws Exception {
    assertThat(System.getenv("SPATIAL_BENCHMARK_ISOLATED"))
        .as("Use make benchmark-spatial; never run this fixture on a personal database")
        .isEqualTo("1");
    seed();
    var report = new LinkedHashMap<String, Object>();
    report.put("recorded_at_utc", java.time.Instant.now().toString());
    report.put(
        "environment",
        Map.of(
            "java",
            System.getProperty("java.version"),
            "os",
            System.getProperty("os.name"),
            "arch",
            System.getProperty("os.arch"),
            "logical_cpus",
            Runtime.getRuntime().availableProcessors(),
            "postgresql",
            jdbc.queryForObject("SHOW server_version", String.class),
            "postgis",
            jdbc.queryForObject("SELECT PostGIS_Lib_Version()", String.class)));
    report.put(
        "dataset",
        Map.of(
            "osm_points",
            50000,
            "user_points",
            1000,
            "roads",
            10000,
            "polygons",
            5000,
            "city_osm_points",
            300,
            "city_user_points",
            80,
            "city_roads",
            200,
            "city_polygons",
            100,
            "road_city_input_vertices",
            80,
            "polygon_input_vertices",
            81));
    report.put(
        "sampling",
        "First sample after INSERT+ANALYZE (not cold cache); then 20 warm SQL/HTTP samples after a separate first HTTP request. EXPLAIN ANALYZE includes instrumentation overhead; HTTP includes full UTF-8 body transfer.");
    report.put("targets", Map.of("sql_p95_ms", 200, "http_p95_ms", 500, "response_bytes", 2097152));
    var viewport = new SpatialQuery(35.3, 52.3, 35.4, 52.4, 13);
    var layers = new LinkedHashMap<String, Object>();
    for (String layer : List.of("points", "roads", "polygons")) {
      var query = SpatialService.query(layer, viewport);
      var first = explain(query);
      var indexes = new ArrayList<String>();
      indexes(first.path("Plan"), indexes);
      String expectedIndex =
          switch (layer) {
            case "points" -> "planet_osm_point_way_idx";
            case "roads" -> "planet_osm_roads_way_idx";
            default -> "planet_osm_polygon_way_idx";
          };
      assertThat(indexes)
          .as("selective actual production SQL for %s", layer)
          .contains(expectedIndex);
      var sqlTimes = new ArrayList<Double>();
      var httpTimes = new ArrayList<Double>();
      int bytes = 0, features = 0;
      String uri = "/api/v1/osm/" + layer + "?bbox=35.3,52.3,35.4,52.4&zoom=13";
      long firstHttpStart = System.nanoTime();
      var firstHttpResponse = client.getForEntity(uri, String.class);
      double firstHttpMs = (System.nanoTime() - firstHttpStart) / 1_000_000d;
      assertThat(firstHttpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      for (int i = 0; i < SAMPLES; i++) {
        sqlTimes.add(explain(query).path("Execution Time").asDouble());
        long start = System.nanoTime();
        var response = client.getForEntity(uri, String.class);
        httpTimes.add((System.nanoTime() - start) / 1_000_000d);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        bytes = response.getBody().getBytes(StandardCharsets.UTF_8).length;
        features = mapper.readTree(response.getBody()).size();
      }
      int expected =
          switch (layer) {
            case "points" -> 380;
            case "roads" -> 200;
            default -> 100;
          };
      assertThat(features).as(layer).isEqualTo(expected);
      var result = new LinkedHashMap<String, Object>();
      result.put("first_sql_ms", first.path("Execution Time").asDouble());
      result.put("first_http_ms", firstHttpMs);
      result.put("warm_sql_p95_ms", p95(sqlTimes));
      result.put("warm_http_p95_ms", p95(httpTimes));
      result.put("features", features);
      result.put("response_bytes", bytes);
      result.put(
          "meets_local_targets", p95(sqlTimes) <= 200 && p95(httpTimes) <= 500 && bytes <= 2097152);
      result.put("indexes", indexes);
      result.put("sql_samples_ms", sqlTimes);
      result.put("http_samples_ms", httpTimes);
      result.put("explain_analyze_buffers", first);
      layers.put(layer, result);
    }
    report.put("layers", layers);
    var overflow =
        client.getForEntity("/api/v1/osm/points?bbox=36,53,36.1,53.1&zoom=13", String.class);
    assertThat(overflow.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(mapper.readTree(overflow.getBody()).path("code").asText())
        .isEqualTo("spatial_limit_exceeded");
    report.put("overflow_status", overflow.getStatusCode().value());
    var points = new ArrayList<List<Double>>();
    for (int i = 0; i < 1000; i++)
      points.add(List.of(35.35 + (i % 2) * 0.0001, 52.34 + (i % 2) * 0.0001));
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var request = new HttpEntity<>(mapper.writeValueAsString(Map.of("points", points)), headers);
    var distanceTimes = new ArrayList<Double>();
    long firstDistanceStart = System.nanoTime();
    var firstDistance = client.postForEntity("/api/v1/osm/distances", request, String.class);
    double firstDistanceMs = (System.nanoTime() - firstDistanceStart) / 1_000_000d;
    assertThat(firstDistance.getStatusCode()).isEqualTo(HttpStatus.OK);
    report.put("first_distance_http_ms", firstDistanceMs);
    for (int i = 0; i < SAMPLES; i++) {
      long start = System.nanoTime();
      var response = client.postForEntity("/api/v1/osm/distances", request, String.class);
      distanceTimes.add((System.nanoTime() - start) / 1_000_000d);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(mapper.readTree(response.getBody()).path("lines").size()).isEqualTo(999);
    }
    report.put("distance_1000_http_p95_ms", p95(distanceTimes));
    report.put("distance_http_samples_ms", distanceTimes);
    Path path = Path.of("target/spatial-benchmark.json");
    Files.createDirectories(path.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    System.out.println("Spatial benchmark written to " + path.toAbsolutePath());
  }

  private JsonNode explain(SpatialService.Sql query) throws Exception {
    String plan =
        jdbc.queryForObject(
            "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + query.sql(),
            String.class,
            query.parameters().toArray());
    return mapper.readTree(plan).get(0);
  }

  private void indexes(JsonNode node, List<String> names) {
    if (node.has("Index Name")) names.add(node.path("Index Name").asText());
    for (var child : node) indexes(child, names);
  }

  private double p95(List<Double> values) {
    var sorted = values.stream().sorted().toList();
    return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
  }

  private void seed() {
    jdbc.update(
        """
      INSERT INTO public.planet_osm_point(osm_id,name,way)
      SELECT 9007000000000000+i,'benchmark-global-'||i,
        ST_Transform(ST_SetSRID(ST_MakePoint(-170+(i%500)*0.68,-70+(i/500)*1.4),4326),3857)
      FROM generate_series(0,49099) i
      """);
    jdbc.update(
        """
      INSERT INTO public.planet_osm_point(osm_id,name,way)
      SELECT 9007100000000000+i,'benchmark-city-'||i,
        ST_Transform(ST_SetSRID(ST_MakePoint(35.305+(i%20)*0.0045,52.305+(i/20)*0.006),4326),3857)
      FROM generate_series(0,299) i
      """);
    jdbc.update(
        """
      INSERT INTO public.planet_osm_point(osm_id,name,way)
      SELECT 9007200000000000+i,'benchmark-overflow-'||i,
        ST_Transform(ST_SetSRID(ST_MakePoint(36.005+(i%30)*0.003,53.005+(i/30)*0.0045),4326),3857)
      FROM generate_series(0,599) i
      """);
    jdbc.update(
        """
      INSERT INTO app.user_points(name,geom)
      SELECT 'benchmark-user-global-'||i,ST_SetSRID(ST_MakePoint(-150+(i%40)*2,-60+(i/40)*4),4326)
      FROM generate_series(0,919) i
      """);
    jdbc.update(
        """
      INSERT INTO app.user_points(name,geom)
      SELECT 'benchmark-user-city-'||i,ST_SetSRID(ST_MakePoint(35.31+(i%10)*0.008,52.31+(i/10)*0.01),4326)
      FROM generate_series(0,79) i
      """);
    jdbc.update(
        """
      INSERT INTO public.planet_osm_roads(osm_id,name,highway,way)
      SELECT 8000000000+i,'benchmark-road-global-'||i,'residential',
        ST_Transform(ST_SetSRID(ST_MakeLine(ST_MakePoint(-170+(i%100)*3.4,-70+(i/100)*1.4),
          ST_MakePoint(-169.99+(i%100)*3.4,-69.99+(i/100)*1.4)),4326),3857)
      FROM generate_series(0,9799) i
      """);
    jdbc.update(
        """
      INSERT INTO public.planet_osm_roads(osm_id,name,highway,way)
      SELECT 8100000000+i,'benchmark-road-city-'||i,'residential',
        ST_Transform(ST_SetSRID(ST_MakeLine(ST_MakePoint(35.305+(i%20)*0.0045+j*0.000035,
          52.305+(i/20)*0.008+0.00015*sin(j*pi()/2)) ORDER BY j),4326),3857)
      FROM generate_series(0,199) i CROSS JOIN generate_series(0,79) j GROUP BY i
      """);
    jdbc.update(
        """
      INSERT INTO public.planet_osm_polygon(osm_id,name,tags,way)
      SELECT 7000000000+i,'benchmark-polygon-global-'||i,hstore('landuse','forest'),
        ST_Buffer(ST_Transform(ST_SetSRID(ST_MakePoint(-170+(i%100)*3.4,-70+(i/100)*2.8),4326),3857),200,'quad_segs=20')
      FROM generate_series(0,4899) i
      """);
    jdbc.update(
        """
      INSERT INTO public.planet_osm_polygon(osm_id,name,tags,way)
      SELECT 7100000000+i,'benchmark-polygon-city-'||i,hstore('landuse','forest'),
        ST_Buffer(ST_Transform(ST_SetSRID(ST_MakePoint(35.307+(i%10)*0.009,52.307+(i/10)*0.009),4326),3857),70,'quad_segs=20')
      FROM generate_series(0,99) i
      """);
    for (String table :
        List.of(
            "public.planet_osm_point",
            "app.user_points",
            "public.planet_osm_roads",
            "public.planet_osm_polygon")) jdbc.execute("ANALYZE " + table);
  }
}
