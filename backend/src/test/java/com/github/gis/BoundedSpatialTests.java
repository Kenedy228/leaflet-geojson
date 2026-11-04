package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BoundedSpatialTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  void validatesSpatialParametersWithoutPermissiveFallback() throws Exception {
    for (String query :
        new String[] {
          "bbox=0,0,0,1",
          "bbox=180,0,-180,1",
          "bbox=0,1,1,0",
          "bbox=NaN,0,1,1",
          "bbox=-181,0,1,1",
          "bbox=0,0,1",
          "bbox=0x1p0,0,2,2",
          "bbox=1d,0,2,2",
          "zoom=23",
          "zoom=1.0",
          "zoom=1&zoom=2",
          "bbox=0,0,1,1&bbox=1,1,2,2"
        }) {
      for (String layer : new String[] {"points", "roads", "polygons"}) {
        var result =
            mvc.perform(get("/api/v1/osm/" + layer + "?" + query))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("code").asText())
            .isEqualTo("invalid_spatial_query");
      }
    }
  }

  @Test
  void filtersBothStoredCoordinateSystemsAndDatelineWithoutDuplicates() throws Exception {
    jdbc.update("DELETE FROM public.planet_osm_point");
    jdbc.update("DELETE FROM app.user_points");
    jdbc.update(
        "INSERT INTO public.planet_osm_point(osm_id,name,way) SELECT n,n::text,ST_Transform(ST_SetSRID(ST_MakePoint(lng,0),4326),3857) FROM (VALUES (1,179.5),(2,-179.5),(3,0.0)) AS p(n,lng)");
    jdbc.update(
        "INSERT INTO app.user_points(name,geom) VALUES ('pole',ST_SetSRID(ST_MakePoint(0,90),4326)),('east',ST_SetSRID(ST_MakePoint(179.6,0),4326))");
    var wrapped = items("points", "179,-1,-179,1", 22);
    assertThat(wrapped.size()).isEqualTo(3);
    assertThat(items("points", "-1,89,1,90", 22).size()).isEqualTo(1);
    assertThat(items("points", "-1,-1,1,1", 22).size()).isEqualTo(1);
  }

  @Test
  void rejects501stFeatureAcrossSourcesRatherThanReturningPartialSuccess() throws Exception {
    jdbc.update("DELETE FROM public.planet_osm_point");
    jdbc.update("DELETE FROM app.user_points");
    jdbc.update(
        "INSERT INTO app.user_points(name,geom) SELECT n::text,ST_SetSRID(ST_MakePoint(10,10),4326) FROM generate_series(1,500) n");
    assertThat(items("points", "9,9,11,11", 22).size()).isEqualTo(500);
    jdbc.update(
        "INSERT INTO public.planet_osm_point(osm_id,name,way) VALUES (1,'overflow',ST_Transform(ST_SetSRID(ST_MakePoint(10,10),4326),3857))");
    var result =
        mvc.perform(get("/api/v1/osm/points?bbox=9,9,11,11"))
            .andExpect(status().isPayloadTooLarge())
            .andReturn();
    assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("code").asText())
        .isEqualTo("spatial_limit_exceeded");
  }

  @Test
  void clipsRoadAndRejectsBoundingBoxFalsePositive() throws Exception {
    jdbc.update("DELETE FROM public.planet_osm_roads");
    jdbc.update(
        "INSERT INTO public.planet_osm_roads(osm_id,name,highway,way) VALUES (1,'crossing','road',ST_Transform(ST_GeomFromText('LINESTRING(-10 0,10 0)',4326),3857)), (2,'false-positive','road',ST_Transform(ST_GeomFromText('LINESTRING(-2 0,0 2)',4326),3857))");
    var roads = items("roads", "-1,-1,1,0.5", 22);
    assertThat(roads.size()).isEqualTo(1);
    var coordinates = mapper.readTree(roads.get(0).path("geojson").asText()).path("coordinates");
    assertThat(coordinates.get(0).get(0).asDouble()).isEqualTo(-1);
    assertThat(coordinates.get(1).get(0).asDouble()).isEqualTo(1);
  }

  @Test
  void boundsReferenceFeatureCountsAndOmitsBoundaryOnlyGeometry() throws Exception {
    for (String layer : new String[] {"roads", "polygons"}) {
      String table = layer.equals("roads") ? "planet_osm_roads" : "planet_osm_polygon";
      String geometry =
          layer.equals("roads")
              ? "LINESTRING(10 10,10.1 10.1)"
              : "POLYGON((10 10,10 10.1,10.1 10.1,10.1 10,10 10))";
      jdbc.update("DELETE FROM public." + table);
      jdbc.update(
          "INSERT INTO public."
              + table
              + "(osm_id,name,way) SELECT n,n::text,ST_Transform(ST_GeomFromText(?,4326),3857) FROM generate_series(1,500) n",
          geometry);
      if (layer.equals("roads")) jdbc.update("UPDATE public." + table + " SET highway='road'");
      assertThat(items(layer, "9,9,11,11", 22).size()).isEqualTo(500);
      jdbc.update("INSERT INTO public." + table + " SELECT * FROM public." + table + " LIMIT 1");
      mvc.perform(get("/api/v1/osm/" + layer).param("bbox", "9,9,11,11"))
          .andExpect(status().isPayloadTooLarge());
      jdbc.update("DELETE FROM public." + table);
      jdbc.update(
          "INSERT INTO public."
              + table
              + "(osm_id,name,way) VALUES (1,'boundary',ST_Transform(ST_GeomFromText(?,4326),3857))",
          geometry);
      if (layer.equals("roads")) jdbc.update("UPDATE public." + table + " SET highway='road'");
      assertThat(items(layer, "10.1,10.1,11,11", 22).size()).isEqualTo(0);
    }
  }

  @Test
  void simplifiesForZoomAndRejectsPerFeatureAndLayerVertexOverflow() throws Exception {
    jdbc.update("DELETE FROM public.planet_osm_roads");
    jdbc.update(
        "INSERT INTO public.planet_osm_roads(osm_id,name,highway,way) SELECT 1,'dense','road',ST_Transform(ST_SetSRID(ST_MakeLine(array_agg(ST_MakePoint(10+i*0.00001,10+(i%2)*0.00001) ORDER BY i)),4326),3857) FROM generate_series(1,6000) i");
    var rejected =
        mvc.perform(get("/api/v1/osm/roads?bbox=9,9,11,11&zoom=22"))
            .andExpect(status().isPayloadTooLarge())
            .andReturn();
    assertThat(mapper.readTree(rejected.getResponse().getContentAsString()).path("code").asText())
        .isEqualTo("spatial_geometry_limit_exceeded");
    var simplified = items("roads", "9,9,11,11", 0);
    assertThat(
            mapper.readTree(simplified.get(0).path("geojson").asText()).path("coordinates").size())
        .isEqualTo(2);
    jdbc.update("DELETE FROM public.planet_osm_roads");
    jdbc.update(
        "INSERT INTO public.planet_osm_roads(osm_id,name,highway,way) SELECT n,'dense','road',ST_Transform(ST_SetSRID(ST_MakeLine(ARRAY(SELECT ST_MakePoint(10+i*0.00001,10+(i%2)*0.00001) FROM generate_series(1,4500) i)),4326),3857) FROM generate_series(1,5) n");
    mvc.perform(get("/api/v1/osm/roads?bbox=9,9,11,11&zoom=22"))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void cancelsBlockedSpatialStatementAndDoesNotChangeSharedJdbcTimeout() throws Exception {
    int timeout = jdbc.getQueryTimeout();
    try (var lock = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) {
        statement.execute("LOCK TABLE public.planet_osm_polygon IN ACCESS EXCLUSIVE MODE");
      }
      var response =
          mvc.perform(get("/api/v1/osm/polygons?bbox=0,0,1,1"))
              .andExpect(status().isServiceUnavailable())
              .andReturn();
      assertThat(mapper.readTree(response.getResponse().getContentAsString()).path("code").asText())
          .isEqualTo("spatial_query_timeout");
      lock.rollback();
    }
    assertThat(jdbc.getQueryTimeout()).isEqualTo(timeout);
    items("polygons", "0,0,1,1", 22);
  }

  private com.fasterxml.jackson.databind.JsonNode items(String layer, String bbox, int zoom)
      throws Exception {
    var response =
        mvc.perform(
                get("/api/v1/osm/" + layer)
                    .param("bbox", bbox)
                    .param("zoom", Integer.toString(zoom)))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(response.getResponse().getContentAsString());
  }
}
