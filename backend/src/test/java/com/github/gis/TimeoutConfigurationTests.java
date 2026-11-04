package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.gis.points.NewPoint;
import com.github.gis.points.PointService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TimeoutConfigurationTests {
  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbc;
  @Autowired PointService points;

  @Test
  void boundsPoolAcquisitionAndDriverSocketWithoutGlobalStatementTimeout() throws Exception {
    HikariDataSource pool = (HikariDataSource) dataSource;
    assertThat(pool.getConnectionTimeout()).isEqualTo(2000);
    assertThat(pool.getValidationTimeout()).isEqualTo(1000);
    assertThat(jdbc.getQueryTimeout()).isEqualTo(-1);
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getNetworkTimeout()).isEqualTo(5000);
      try (var statement = connection.createStatement()) {
        assertThat(statement.getQueryTimeout()).isZero();
      }
    }
  }

  @Test
  void blockedCreateIsCancelledAndRolledBack() throws Exception {
    String name = "bounded-create-" + UUID.randomUUID();
    try (var lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) {
        statement.execute("LOCK TABLE app.user_points IN ACCESS EXCLUSIVE MODE");
      }
      long started = System.nanoTime();
      assertThatThrownBy(() -> points.create(List.of(new NewPoint(name, 52.34, 35.35))))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
      lock.rollback();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.user_points WHERE name=?", Long.class, name))
        .isZero();
  }

  @Test
  void batchBudgetIncludesEarlierWritesAndRollsBackTheWholeBatch() {
    String name = "bounded-batch-" + UUID.randomUUID();
    jdbc.execute(
        "CREATE FUNCTION app.diagnostics_test_write_delay() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(3); RETURN NEW; END $$");
    try {
      jdbc.execute(
          "CREATE TRIGGER diagnostics_test_write_delay BEFORE INSERT ON app.user_points FOR EACH ROW WHEN (NEW.name LIKE 'bounded-batch-%') EXECUTE FUNCTION app.diagnostics_test_write_delay()");
      long started = System.nanoTime();
      assertThatThrownBy(
              () ->
                  points.create(
                      List.of(
                          new NewPoint(name + "-first", 52.34, 35.35),
                          new NewPoint(name + "-second", 52.34, 35.35))))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM app.user_points WHERE name IN (?,?)",
                  Long.class,
                  name + "-first",
                  name + "-second"))
          .isZero();
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS diagnostics_test_write_delay ON app.user_points");
      jdbc.execute("DROP FUNCTION app.diagnostics_test_write_delay()");
      jdbc.update(
          "DELETE FROM app.user_points WHERE name IN (?,?)", name + "-first", name + "-second");
    }
  }

  @Test
  void blockedDeleteIsCancelledAndPreservesPoint() throws Exception {
    UUID id =
        jdbc.queryForObject(
            "INSERT INTO app.user_points(name,geom) VALUES ('bounded-delete',ST_SetSRID(ST_MakePoint(35.35,52.34),4326)) RETURNING id",
            UUID.class);
    try {
      try (var lock = dataSource.getConnection()) {
        lock.setAutoCommit(false);
        try (var statement = lock.createStatement()) {
          statement.execute("LOCK TABLE app.user_points IN ACCESS EXCLUSIVE MODE");
        }
        long started = System.nanoTime();
        assertThatThrownBy(() -> points.delete("user:" + id))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        lock.rollback();
      }
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM app.user_points WHERE id=?", Long.class, id))
          .isEqualTo(1);
    } finally {
      jdbc.update("DELETE FROM app.user_points WHERE id=?", id);
    }
  }
}
