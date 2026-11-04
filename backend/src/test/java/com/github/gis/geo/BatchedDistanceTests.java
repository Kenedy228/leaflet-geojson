package com.github.gis.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

@SpringBootTest
class BatchedDistanceTests {
  @Autowired DataSource database;

  @Test
  void thousandOrderedCoordinatesUseOneRealPostgisStatement() {
    var statements = new AtomicInteger();
    // Observe calls while delegating every operation to the real isolated PostGIS connection.
    var observed =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            Connection connection = database.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("prepareStatement")) statements.incrementAndGet();
                      try {
                        return method.invoke(connection, args);
                      } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                      }
                    });
          }

          @Override
          public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException();
          }
        };
    var coordinates = new ArrayList<List<Double>>();
    for (int i = 0; i < 1000; i++) coordinates.add(List.of((i % 2 == 0 ? 179.9 : -179.9), 0d));
    var result =
        new SpatialService(new JdbcTemplate(observed)).distance(new DistanceRequest(coordinates));
    assertThat(statements.get()).isEqualTo(1);
    assertThat(result.lines()).hasSize(999);
    assertThat(result.totalDistance())
        .isCloseTo(22263.898158654716 * 999, org.assertj.core.data.Offset.offset(0.02));
    for (int i = 0; i < 999; i++) {
      assertThat(result.lines().get(i).from()).isEqualTo(coordinates.get(i));
      assertThat(result.lines().get(i).to()).isEqualTo(coordinates.get(i + 1));
    }
  }
}
