package com.github.gis;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"EDITING_ENABLED=true", "EDITOR_USERNAME=editor", "EDITOR_PASSWORD=test-editor-password-only"})
class DemoDatasetTests {
    @Autowired private JdbcTemplate jdbc;
    private TestRestTemplate client;
    @LocalServerPort private int port;
    private final String userName = "demo-invariant-test-" + UUID.randomUUID();
    private static final long FOREIGN_ID = 9007199254741234L;

    @BeforeEach
    void loginEditor() { client = EditorHttpClient.login(port); }

    @AfterEach
    void cleanOnlyTestData() {
        for (String table : List.of("planet_osm_point", "planet_osm_roads", "planet_osm_polygon")) {
            jdbc.update("DELETE FROM public." + table + " WHERE tags ? 'leaflet:demo'");
        }
        jdbc.update("DELETE FROM public.planet_osm_point WHERE osm_id = ? AND name = ?",
                FOREIGN_ID, userName);
        jdbc.update("DELETE FROM app.user_points WHERE name = ?", userName);
    }

    @Test
    void repeatedSeedResetsReferenceDataWithoutDuplicatingOrChangingUserPoints() throws IOException {
        var created = client.postForEntity("/api/v1/osm/points/add",
                List.of(Map.of("name", userName, "lat", 52.339203, "lng", 35.350871)), JsonNode.class);
        String userId = created.getBody().path("points").get(0).path("id").asText();
        seed();
        jdbc.update("UPDATE public.planet_osm_point SET name = 'Edited demo' WHERE osm_id = 2469931302");
        seed();
        assertThat(count("planet_osm_point")).isEqualTo(3);
        assertThat(count("planet_osm_roads")).isEqualTo(2);
        assertThat(count("planet_osm_polygon")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT osm_id) FROM public.planet_osm_point",
                Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT name FROM public.planet_osm_point WHERE osm_id = 2469931302",
                String.class)).isEqualTo("ВТБ");
        var saved = jdbc.queryForMap("SELECT 'user:' || id::text AS id, ST_X(geom) AS lng, ST_Y(geom) AS lat "
                + "FROM app.user_points WHERE name = ?", userName);
        assertThat(saved.get("id")).isEqualTo(userId);
        assertThat(saved.get("lng")).isEqualTo(35.350871);
        assertThat(saved.get("lat")).isEqualTo(52.339203);
    }

    @Test
    void seedRefusesForeignImportWithoutDeletingTheExistingDataset() throws IOException {
        seed();
        jdbc.update("INSERT INTO public.planet_osm_point(osm_id,name,way) VALUES (?, ?, "
                + "ST_Transform(ST_SetSRID(ST_MakePoint(35,52),4326),3857))", FOREIGN_ID, userName);
        assertThatThrownBy(this::seed).isInstanceOf(DataAccessException.class);
        assertThat(count("planet_osm_point")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT name FROM public.planet_osm_point WHERE osm_id = ?",
                String.class, FOREIGN_ID)).isEqualTo(userName);
        assertThat(count("planet_osm_roads")).isEqualTo(2);
        assertThat(count("planet_osm_polygon")).isEqualTo(2);
    }

    @Test
    void seededLayersAreReadableAsWgs84GeoJson() throws IOException {
        seed();
        var points = client.getForObject("/api/v1/osm/points", JsonNode.class);
        assertThat(points.size()).isEqualTo(3);
        assertThat(points.get(0).path("source").asText()).isEqualTo("osm");
        for (String endpoint : List.of("roads", "polygons")) {
            var data = client.getForObject("/api/v1/osm/" + endpoint, JsonNode.class);
            assertThat(data.size()).isEqualTo(2);
            assertThat(data.get(0).path("geojson").asText()).contains("coordinates");
        }
        assertThat(jdbc.queryForObject("SELECT bool_and(ST_IsValid(way) AND ST_SRID(way)=3857) "
                + "FROM public.planet_osm_polygon", Boolean.class)).isTrue();
        // These independent bounds catch a missing transform to WGS84 at the API boundary.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var point : points) {
            var geometry = mapper.readTree(point.path("geojson").asText());
            assertThat(geometry.path("coordinates").get(0).asDouble()).isBetween(35.3, 35.4);
            assertThat(geometry.path("coordinates").get(1).asDouble()).isBetween(52.3, 52.4);
        }
    }

    @Test
    void disabledDemoSeedLeavesRealImportsUntouched() throws IOException {
        jdbc.update("INSERT INTO public.planet_osm_point(osm_id,name,way) VALUES (?, ?, "
                + "ST_Transform(ST_SetSRID(ST_MakePoint(35,52),4326),3857))", FOREIGN_ID, userName);
        jdbc.execute(seedSql().replace("${demo_data_enabled}", "false"));
        assertThat(count("planet_osm_point")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM public.planet_osm_point WHERE osm_id = ?",
                String.class, FOREIGN_ID)).isEqualTo(userName);
        assertThat(count("planet_osm_roads")).isZero();
        assertThat(count("planet_osm_polygon")).isZero();
    }

    @Test
    void referenceSchemaRejectsWrongProjectionAndGeometryKind() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO public.planet_osm_point(osm_id,way) "
                + "VALUES (?,ST_SetSRID(ST_MakePoint(35,52),4326))", FOREIGN_ID))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO public.planet_osm_roads(osm_id,way) "
                + "VALUES (?,ST_SetSRID(ST_MakePoint(35,52),3857))", FOREIGN_ID))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO public.planet_osm_polygon(osm_id,way) "
                + "VALUES (?,ST_SetSRID(ST_MakePoint(35,52),3857))", FOREIGN_ID))
                .isInstanceOf(DataAccessException.class);
    }

    private void seed() throws IOException {
        jdbc.execute(seedSql().replace("${demo_data_enabled}", "true"));
    }

    private String seedSql() throws IOException {
        return new ClassPathResource("db/demo/R__reference_dataset.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM public." + table, Long.class);
    }
}
