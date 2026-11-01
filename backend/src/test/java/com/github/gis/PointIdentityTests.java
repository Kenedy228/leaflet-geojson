package com.github.gis;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointIdentityTests {
    private static final String API = "/api/v1/osm/points";
    private static final long OSM_ID = 9007199254740993L;
    private static final long LEGACY_ID = -1735689600000L;

    @Autowired private TestRestTemplate client;
    @Autowired private JdbcTemplate jdbc;
    private String prefix;

    @BeforeEach
    void addImportedFixture() {
        prefix = "point-model-test-" + UUID.randomUUID();
        insertOsm(OSM_ID, prefix + "-osm");
    }

    @AfterEach
    void removeOnlyTestRecords() {
        jdbc.update("DELETE FROM public.planet_osm_point WHERE osm_id IN (?, ?, ?)",
                OSM_ID, LEGACY_ID, LEGACY_ID - 1);
        // Also cleans up records created by the pre-migration implementation in the RED run.
        jdbc.update("DELETE FROM public.planet_osm_point WHERE name LIKE ?", prefix + "%");
        if (jdbc.queryForObject("SELECT to_regclass('app.user_points') IS NOT NULL", Boolean.class)) {
            jdbc.update("DELETE FROM app.user_points WHERE name LIKE ?", prefix + "%");
        }
    }

    @Test
    void returnsDatabaseIdsForBatchWithoutWritingOsm() {
        var response = client.postForEntity(API + "/add", List.of(
                input(prefix + "-a"), input(prefix + "-b")), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = response.getBody();
        assertThat(body.path("inserted").asInt()).isEqualTo(2);
        var ids = new HashSet<String>();
        for (var point : body.path("points")) {
            assertThat(point.path("source").asText()).isEqualTo("user");
            assertThat(point.path("osm_id").isNull()).isTrue();
            String id = point.path("id").asText();
            assertThat(id).startsWith("user:");
            assertThat(UUID.fromString(id.substring(5)).toString()).isEqualTo(id.substring(5));
            ids.add(id);
        }
        assertThat(ids).hasSize(2);
        assertThat(count("app.user_points", prefix + "-%")).isEqualTo(2);
        assertThat(count("public.planet_osm_point", prefix + "-%")).isEqualTo(1);
    }

    @Test
    void exposesOsmIdsAsStringsWithoutJavascriptPrecisionLoss() {
        var point = findByName(prefix + "-osm");
        assertThat(point.path("id").asText()).isEqualTo("osm:9007199254740993");
        assertThat(point.path("source").asText()).isEqualTo("osm");
        assertThat(point.path("osm_id").isTextual()).isTrue();
        assertThat(point.path("osm_id").asText()).isEqualTo("9007199254740993");
    }

    @Test
    void refusesImportedAndUnqualifiedIdsAndDeletesOnlyRequestedUserPoint() {
        assertThat(delete("osm:" + OSM_ID)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(Long.toString(OSM_ID))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delete("user:not-a-uuid")).isEqualTo(HttpStatus.BAD_REQUEST);
        String first = create(prefix + "-first");
        String second = create(prefix + "-second");
        assertThat(delete(first)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(first)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(findByName(prefix + "-second").path("id").asText()).isEqualTo(second);
        assertThat(findByName(prefix + "-osm").path("id").asText()).isEqualTo("osm:" + OSM_ID);
    }

    @Test
    void concurrentRequestsReceiveDistinctDatabaseIds() throws Exception {
        var ids = new HashSet<String>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var requests = new ArrayList<Callable<String>>();
            for (int i = 0; i < 24; i++) {
                String name = prefix + "-parallel-" + i;
                requests.add(() -> create(name));
            }
            for (var future : pool.invokeAll(requests)) {
                ids.add(future.get(20, TimeUnit.SECONDS));
            }
        }
        assertThat(ids).hasSize(24);
        assertThat(count("app.user_points", prefix + "-parallel-%")).isEqualTo(24);
        assertThat(count("public.planet_osm_point", prefix + "-parallel-%")).isZero();
    }

    @Test
    void movingExplicitLegacyIdsPreservesCollisionsAndIsRepeatable() {
        insertOsm(LEGACY_ID, prefix + "-legacy-a");
        insertOsm(LEGACY_ID, prefix + "-legacy-b");
        insertOsm(LEGACY_ID - 1, prefix + "-not-selected");
        assertThat(migrate(LEGACY_ID)).isEqualTo(2);
        assertThat(migrate(LEGACY_ID)).isZero();
        assertThat(count("public.planet_osm_point", prefix + "-legacy-%")).isZero();
        assertThat(count("app.user_points", prefix + "-legacy-%")).isEqualTo(2);
        assertThat(findByName(prefix + "-legacy-a").path("id").asText())
                .isNotEqualTo(findByName(prefix + "-legacy-b").path("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.user_points WHERE legacy_osm_id = ?",
                Long.class, LEGACY_ID)).isEqualTo(2);
        assertThat(findByName(prefix + "-not-selected").path("source").asText()).isEqualTo("osm");
        assertThat(jdbc.queryForObject("SELECT ST_SRID(geom) FROM app.user_points WHERE name = ?",
                Integer.class, prefix + "-legacy-a")).isEqualTo(4326);
    }

    @Test
    void legacyMigrationRejectsPositiveIdsAndRollsBackFailedMoves() {
        assertThatThrownBy(() -> migrate(OSM_ID)).isInstanceOf(DataAccessException.class);
        insertOsm(LEGACY_ID, " ");
        assertThatThrownBy(() -> migrate(LEGACY_ID)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.planet_osm_point WHERE osm_id = ?",
                Long.class, LEGACY_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.user_points WHERE legacy_osm_id = ?",
                Long.class, LEGACY_ID)).isZero();
    }

    @Test
    void replacingImportedRecordDoesNotRemoveUserPoint() {
        String id = create(prefix + "-persistent");
        jdbc.update("DELETE FROM public.planet_osm_point WHERE osm_id = ?", OSM_ID);
        insertOsm(OSM_ID, prefix + "-reimported");
        assertThat(findByName(prefix + "-persistent").path("id").asText()).isEqualTo(id);
        assertThat(findByName(prefix + "-reimported").path("source").asText()).isEqualTo("osm");
    }

    @Test
    void databaseFailureRollsBackTheEntireBatch() {
        String rejected = prefix + "-reject";
        jdbc.execute("ALTER TABLE app.user_points ADD CONSTRAINT reject_test_point CHECK (name <> '"
                + rejected + "')");
        try {
            var response = client.postForEntity(API + "/add", List.of(
                    input(prefix + "-rollback"), input(rejected)), JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(count("app.user_points", prefix + "-%")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE app.user_points DROP CONSTRAINT reject_test_point");
        }
    }

    private String create(String name) {
        var response = client.postForEntity(API + "/add", List.of(input(name)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("points").get(0).path("id").asText();
    }

    private Map<String, Object> input(String name) {
        return Map.of("name", name, "lat", 52.339203, "lng", 35.350871);
    }

    private HttpStatus delete(String id) {
        return (HttpStatus) client.exchange(API + "/{id}", HttpMethod.DELETE,
                null, Void.class, id).getStatusCode();
    }

    private JsonNode findByName(String name) {
        for (var point : client.getForObject(API, JsonNode.class)) {
            if (name.equals(point.path("name").asText())) return point;
        }
        throw new AssertionError("Point not found: " + name);
    }

    private void insertOsm(long id, String name) {
        jdbc.update("""
                INSERT INTO public.planet_osm_point(osm_id, name, way)
                VALUES (?, ?, ST_Transform(ST_SetSRID(ST_MakePoint(35.350871,52.339203),4326),3857))
                """, id, name);
    }

    private int migrate(long id) {
        return jdbc.queryForObject("SELECT app.migrate_legacy_user_points(ARRAY[?]::bigint[])",
                Integer.class, id);
    }

    private long count(String table, String pattern) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE name LIKE ?",
                Long.class, pattern);
    }
}
