package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class HealthDiagnosticsTests {
  @Autowired TestRestTemplate client;
  @Autowired MockMvc mvc;
  @Autowired ApplicationContext context;

  @Test
  void databaseProbeBoundsItsStatementAndClosesEveryResourceOnFailure() throws Exception {
    DataSource source = mock(DataSource.class);
    var connection = mock(java.sql.Connection.class);
    var statement = mock(java.sql.Statement.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery("SELECT 1")).thenThrow(new SQLException("health-secret-canary"));
    Class<?> indicatorClass =
        context.getBean(HealthContributorRegistry.class).getContributor("db").getClass();
    HealthIndicator indicator =
        (HealthIndicator) indicatorClass.getConstructor(DataSource.class).newInstance(source);
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(indicatorClass);
    var logs =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      var health = indicator.health();
      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(health.getDetails()).isEmpty();
      assertThat(logs.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("database_unavailable");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getKeyValuePairs())
                    .anySatisfy(
                        pair -> {
                          assertThat(pair.key).isEqualTo("event");
                          assertThat(pair.value).isEqualTo("database_unavailable");
                        });
              });
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
    verify(statement).setQueryTimeout(1);
    verify(statement).close();
    verify(connection).close();
  }

  @Test
  void databaseProbeClosesResultAfterSuccess() throws Exception {
    DataSource source = mock(DataSource.class);
    var connection = mock(java.sql.Connection.class);
    var statement = mock(java.sql.Statement.class);
    var result = mock(java.sql.ResultSet.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery("SELECT 1")).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getInt(1)).thenReturn(1);
    Class<?> indicatorClass =
        context.getBean(HealthContributorRegistry.class).getContributor("db").getClass();
    HealthIndicator indicator =
        (HealthIndicator) indicatorClass.getConstructor(DataSource.class).newInstance(source);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    verify(result).close();
    verify(statement).close();
    verify(connection).close();
  }

  @Test
  void probesReturnOnlyStatusWithTheRealDatabase() {
    for (String probe : new String[] {"", "/liveness", "/readiness"}) {
      for (String mediaType :
          new String[] {
            "application/json",
            "application/vnd.spring-boot.actuator.v3+json",
            "application/vnd.spring-boot.actuator.v2+json"
          }) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Accept", mediaType);
        var response =
            client.exchange(
                "/actuator/health" + probe,
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers),
                String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
      }
    }
  }

  @Test
  void editorCannotRetrieveHealthDetails() throws Exception {
    for (String probe : new String[] {"", "/liveness", "/readiness"}) {
      var result =
          mvc.perform(get("/actuator/health" + probe).with(user("editor").roles("EDITOR")))
              .andExpect(status().isOk())
              .andReturn();
      assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"status\":\"UP\"}");
    }
  }

  @Test
  void diagnosticEndpointsAndDiscoveryAreAbsent() {
    for (String endpoint :
        new String[] {
          "",
          "/env",
          "/configprops",
          "/beans",
          "/metrics",
          "/loggers",
          "/heapdump",
          "/threaddump",
          "/mappings",
          "/shutdown"
        }) {
      assertThat(client.getForEntity("/actuator" + endpoint, String.class).getStatusCode().value())
          .isEqualTo(404);
    }
  }

  @Test
  void refusingTrafficMakesReadinessUnavailableWithoutChangingLiveness() {
    AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
    try {
      for (String probe : new String[] {"", "/readiness"}) {
        var response = client.getForEntity("/actuator/health" + probe, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");
      }
      var alive = client.getForEntity("/actuator/health/liveness", String.class);
      assertThat(alive.getStatusCode().value()).isEqualTo(200);
      assertThat(alive.getBody()).isEqualTo("{\"status\":\"UP\"}");
    } finally {
      AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
    }
  }
}
