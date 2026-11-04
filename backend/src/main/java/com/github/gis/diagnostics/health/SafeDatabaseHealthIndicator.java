package com.github.gis.diagnostics.health;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** A minimal database probe that never publishes JDBC metadata or exception details. */
@Component("dbHealthIndicator")
public final class SafeDatabaseHealthIndicator implements HealthIndicator {
  private static final Logger LOG = LoggerFactory.getLogger(SafeDatabaseHealthIndicator.class);
  private final DataSource dataSource;

  public SafeDatabaseHealthIndicator(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Health health() {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(1);
      try (var result = statement.executeQuery("SELECT 1")) {
        if (result.next() && result.getInt(1) == 1) {
          return Health.up().build();
        }
      }
    } catch (SQLException exception) {
      // Neither SQLState text nor the exception chain is suitable for public diagnostics.
    }
    LOG.atWarn().addKeyValue("event", "database_unavailable").log("database_unavailable");
    return Health.down().build();
  }
}
