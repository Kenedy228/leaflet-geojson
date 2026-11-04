package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpatialApiTests {
  private static final long OSM_ID = 9007199254742001L;
  private static final double METRES_TOLERANCE = 0.02;
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  static Stream<Arguments> geodesics() {
    // Independent references and tolerances: docs/testing.md. Never derive these from SQL output.
    return Stream.of(
        Arguments.of(
            "ordered closed route with unequal legs",
            List.of(List.of(0d, 0d), List.of(1d, 0d), List.of(1.25, 0d), List.of(0d, 0d)),
            new double[] {111319.49079327357, 27829.87269831839, 139149.36349159197}),
        Arguments.of(
            "near-antipodal Wellington to Salamanca",
            List.of(List.of(174.81, -41.32), List.of(-5.50, 40.96)),
            new double[] {19959679.267}),
        Arguments.of(
            "short dateline crossing followed by a repeated point",
            List.of(List.of(179.9, 0d), List.of(-179.9, 0d), List.of(-179.9, 0d)),
            new double[] {22263.898158654716, 0d}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("geodesics")
  void returnsOrderedWgs84SpheroidalMetres(
      String scenario, List<List<Double>> points, double[] expectedMetres) throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/osm/distances")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("points", points))))
            .andExpect(status().isOk())
            .andReturn();
    var result = mapper.readTree(response.getResponse().getContentAsString());
    assertThat(result.path("lines").isArray()).isTrue();
    assertThat(result.path("lines").size()).isEqualTo(points.size() - 1);
    for (int i = 0; i < expectedMetres.length; i++) {
      var segment = result.path("lines").get(i);
      assertThat(segment.path("from")).isEqualTo(mapper.valueToTree(points.get(i)));
      assertThat(segment.path("to")).isEqualTo(mapper.valueToTree(points.get(i + 1)));
      assertThat(segment.path("distance").isNumber()).isTrue();
      assertThat(segment.path("distance").asDouble())
          .as("%s: segment %s", scenario, i)
          .isCloseTo(expectedMetres[i], within(METRES_TOLERANCE));
    }
    assertThat(result.path("totalDistance").isNumber()).isTrue();
    assertThat(result.path("totalDistance").asDouble())
        .isCloseTo(Arrays.stream(expectedMetres).sum(), within(METRES_TOLERANCE));
  }

  static Stream<Arguments> geometries() {
    return Stream.of(
        Arguments.of(
            "Point", "points", "planet_osm_point", "POINT(35.125 -22.75)", "[35.125,-22.75]"),
        Arguments.of(
            "LineString",
            "roads",
            "planet_osm_roads",
            "LINESTRING(35 -22,36 -23,37 -24)",
            "[[35,-22],[36,-23],[37,-24]]"),
        Arguments.of(
            "MultiLineString",
            "roads",
            "planet_osm_roads",
            "MULTILINESTRING((35 -22,36 -23),(37 -24,38 -25,39 -26))",
            "[[[35,-22],[36,-23]],[[37,-24],[38,-25],[39,-26]]]"),
        Arguments.of(
            "Polygon",
            "polygons",
            "planet_osm_polygon",
            "POLYGON((35 -22,35 -23,36 -23,36 -22,35 -22),"
                + "(35.2 -22.2,35.4 -22.2,35.4 -22.4,35.2 -22.4,35.2 -22.2))",
            "[[[35,-22],[35,-23],[36,-23],[36,-22],[35,-22]],"
                + "[[35.2,-22.2],[35.4,-22.2],[35.4,-22.4],[35.2,-22.4],[35.2,-22.2]]]"),
        Arguments.of(
            "MultiPolygon",
            "polygons",
            "planet_osm_polygon",
            "MULTIPOLYGON(((35 -22,35 -23,36 -23,35 -22))," + "((38 -25,38 -26,39 -26,38 -25)))",
            "[[[[35,-22],[35,-23],[36,-23],[35,-22]]],"
                + "[[[38,-25],[38,-26],[39,-26],[38,-25]]]]"));
  }

  @ParameterizedTest(name = "{0}: all vertices and rings survive the 3857 to 4326 boundary")
  @MethodSource("geometries")
  void readsEverySupportedGeometryAsWgs84(
      String type, String endpoint, String table, String wkt, String expectedCoordinates)
      throws Exception {
    String name = "spatial-contract-" + type;
    // Tables come exclusively from the fixed method source, never from request input.
    jdbc.update(
        "INSERT INTO public."
            + table
            + "(osm_id, name, tags, way) "
            + "VALUES (?, ?, hstore('landuse','forest'), "
            + "ST_Transform(ST_GeomFromText(?,4326),3857))",
        OSM_ID,
        name,
        wkt);
    if (endpoint.equals("roads")) {
      jdbc.update(
          "UPDATE public.planet_osm_roads SET highway='residential' WHERE osm_id=?", OSM_ID);
    }
    var response =
        mvc.perform(get("/api/v1/osm/" + endpoint)).andExpect(status().isOk()).andReturn();
    var items = mapper.readTree(response.getResponse().getContentAsString());
    assertThat(items.isArray()).isTrue();
    JsonNode found = null;
    for (var item : items) {
      if (item.path("name").asText().equals(name)) {
        assertThat(found).as("the imported row must appear exactly once").isNull();
        found = item;
      }
    }
    assertThat(found).as("%s imported feature", type).isNotNull();
    var geometry = mapper.readTree(found.path("geojson").asText());
    assertThat(geometry.path("type").asText()).isEqualTo(type);
    assertCoordinates(
        geometry.path("coordinates"), mapper.readTree(expectedCoordinates), "$coordinates");
    if (endpoint.equals("points")) {
      assertThat(found.path("id").asText()).isEqualTo("osm:" + OSM_ID);
      assertThat(found.path("source").asText()).isEqualTo("osm");
      assertThat(found.path("osm_id").isTextual()).isTrue();
      assertThat(found.path("osm_id").asText()).isEqualTo(Long.toString(OSM_ID));
    } else if (endpoint.equals("roads")) {
      assertThat(found.path("highway").asText()).isEqualTo("residential");
    } else {
      assertThat(found.path("landuse_type").asText()).isEqualTo("forest");
    }
  }

  private void assertCoordinates(JsonNode actual, JsonNode expected, String path) {
    if (expected.isArray()) {
      assertThat(actual.isArray()).as(path).isTrue();
      assertThat(actual.size()).as(path).isEqualTo(expected.size());
      for (int i = 0; i < expected.size(); i++) {
        assertCoordinates(actual.get(i), expected.get(i), path + "[" + i + "]");
      }
    } else {
      assertThat(actual.isNumber()).as(path).isTrue();
      assertThat(actual.asDouble()).as(path).isCloseTo(expected.asDouble(), within(1e-8));
    }
  }
}
