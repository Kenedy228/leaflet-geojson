package com.github.gis;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.gis.controllers.OSMController;
import com.github.gis.geo.SpatialQuery;
import com.github.gis.geo.SpatialService;
import com.github.gis.points.PointService;
import com.github.gis.security.EditingAccess;
import com.github.gis.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OSMController.class)
@Import({SecurityConfiguration.class, EditingAccess.class})
class ApiErrorTranslationTests {
  @Autowired MockMvc mvc;
  // These exercise error translation; real SQL rollback is covered in PointIdentityTests.
  @MockitoBean PointService points;
  @MockitoBean SpatialService spatial;

  @Test
  void unavailableDatabaseHasSafe503WithoutConnectionDetails() throws Exception {
    when(spatial.points(SpatialQuery.world()))
        .thenThrow(new DataAccessResourceFailureException("jdbc:private-test-secret"));
    mvc.perform(get("/api/v1/osm/points"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("service_unavailable"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(content().string(not(containsString("private-test-secret"))));
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> databaseFailures() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(
            new org.springframework.transaction.CannotCreateTransactionException(
                "private-test-secret"),
            503,
            "database_unavailable"),
        org.junit.jupiter.params.provider.Arguments.of(
            new org.springframework.dao.QueryTimeoutException("private-test-secret"),
            503,
            "database_timeout"),
        org.junit.jupiter.params.provider.Arguments.of(
            new org.springframework.transaction.TransactionTimedOutException("private-test-secret"),
            503,
            "database_timeout"),
        org.junit.jupiter.params.provider.Arguments.of(
            new org.springframework.transaction.TransactionSystemException("private-test-secret"),
            500,
            "internal_error"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("databaseFailures")
  void transactionAndQueryFailuresHaveExplicitSafeHttpTranslation(
      RuntimeException failure, int statusCode, String reason) throws Exception {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(com.github.gis.api.ApiExceptionHandler.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
          @Override
          protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
          }
        };
    appender.start();
    logger.addAppender(appender);
    when(spatial.points(SpatialQuery.world())).thenThrow(failure);
    try {
      var response =
          mvc.perform(get("/api/v1/osm/points"))
              .andExpect(status().is(statusCode))
              .andExpect(
                  jsonPath("$.code")
                      .value(statusCode == 503 ? "service_unavailable" : "internal_error"))
              .andExpect(jsonPath("$.length()").value(2))
              .andExpect(content().string(not(containsString("private-test-secret"))))
              .andReturn()
              .getResponse();
      org.assertj.core.api.Assertions.assertThat(appender.list).hasSize(1);
      var event = appender.list.getFirst();
      org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy()).isNull();
      org.assertj.core.api.Assertions.assertThat(event.getMDCPropertyMap().get("request_id"))
          .isEqualTo(response.getHeader("X-Request-ID"));
      org.assertj.core.api.Assertions.assertThat(event.getKeyValuePairs())
          .anySatisfy(
              pair -> {
                org.assertj.core.api.Assertions.assertThat(pair.key).isEqualTo("reason");
                org.assertj.core.api.Assertions.assertThat(pair.value).isEqualTo(reason);
              });
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void unexpectedExceptionHasSafe500WithoutInternalMessage() throws Exception {
    when(spatial.points(SpatialQuery.world()))
        .thenThrow(new IllegalStateException("stack/private-test-secret"));
    mvc.perform(get("/api/v1/osm/points"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("internal_error"))
        .andExpect(content().string(not(containsString("private-test-secret"))));
  }
}
