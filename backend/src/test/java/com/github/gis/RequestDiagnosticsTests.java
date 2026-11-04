package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.github.gis.controllers.OSMController;
import com.github.gis.geo.SpatialQuery;
import com.github.gis.geo.SpatialService;
import com.github.gis.points.PointService;
import com.github.gis.security.EditingAccess;
import com.github.gis.security.SecurityConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
@WebMvcTest(controllers = OSMController.class)
@Import({SecurityConfiguration.class, EditingAccess.class})
class RequestDiagnosticsTests {
  @Autowired MockMvc mvc;
  @MockitoBean PointService points;
  @MockitoBean SpatialService spatial;

  @Test
  void assignsFreshServerCorrelationEvenToSecurityErrorsAndCleansMdc() throws Exception {
    var first =
        mvc.perform(post("/api/v1/osm/points").header("X-Request-ID", "private-canary"))
            .andReturn()
            .getResponse();
    assertThat(first.getStatus()).isEqualTo(403);
    var id = first.getHeader("X-Request-ID");
    assertThat(id).isNotNull();
    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    assertThat(MDC.get("request_id")).isNull();
    var second = mvc.perform(get("/unknown/private-canary")).andReturn().getResponse();
    assertThat(second.getHeader("X-Request-ID")).isNotEqualTo(id).isNotNull();
  }

  @Test
  void committedResponseDoesNotLetInheritedAdviceLogRawException() throws Exception {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(com.github.gis.api.ApiExceptionHandler.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    response.setCommitted(true);
    try {
      new com.github.gis.api.ApiExceptionHandler()
          .handleException(
              new org.springframework.web.HttpRequestMethodNotSupportedException("private-canary"),
              new org.springframework.web.context.request.ServletWebRequest(request, response));
      assertThat(appender.list)
          .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("private-canary"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void logsOnlyAllowedMetadataForRejectedAndUnknownRequests(
      org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                "com.github.gis.diagnostics.logging.RequestDiagnosticsFilter");
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
    try {
      var response =
          mvc.perform(
                  post("/api/v1/osm/points/add?secret=private-canary")
                      .header("Authorization", "private-canary")
                      .content("private-canary"))
              .andReturn()
              .getResponse();
      assertThat(output.getOut()).contains("\"event\":\"request_completed\"");
      assertThat(output.getAll()).doesNotContain("Duplicate nested pairs");
      assertThat(appender.list).hasSize(1);
      var event = appender.list.getFirst();
      assertThat(event.getFormattedMessage()).isEqualTo("request_completed");
      assertThat(event.getThrowableProxy()).isNull();
      assertThat(event.getMDCPropertyMap().get("request_id"))
          .isEqualTo(response.getHeader("X-Request-ID"));
      assertThat(event.getKeyValuePairs().toString()).doesNotContain("private-canary", "secret=");
      assertThat(event.getKeyValuePairs())
          .anySatisfy(
              pair -> {
                assertThat(pair.key).isEqualTo("route");
                assertThat(pair.value).isEqualTo("points_create");
              });
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void idIsAvailableWhileHandlingFailureAndPreviousMdcIsRestored() throws Exception {
    when(spatial.points(SpatialQuery.world()))
        .thenAnswer(
            call -> {
              assertThat(MDC.get("request_id")).isNotNull();
              throw new IllegalStateException("private-canary");
            });
    MDC.put("request_id", "outer-context");
    try {
      var response = mvc.perform(get("/api/v1/osm/points")).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(500);
      assertThat(response.getContentAsString()).doesNotContain("private-canary", "request_id");
      assertThat(MDC.get("request_id")).isEqualTo("outer-context");
    } finally {
      MDC.remove("request_id");
    }
  }
}
