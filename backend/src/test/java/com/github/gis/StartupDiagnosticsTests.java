package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationFailedEvent;

class StartupDiagnosticsTests {
  @Test
  void earlyFailureIsCategoricalOnceWithoutExceptionDetails() throws Exception {
    var diagnostics =
        Class.forName("com.github.gis.diagnostics.logging.StartupDiagnostics")
            .getConstructor()
            .newInstance();
    var logger = (Logger) LoggerFactory.getLogger(diagnostics.getClass());
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    var listener = (org.springframework.context.ApplicationListener) diagnostics;
    var event =
        new ApplicationFailedEvent(
            new SpringApplication(GisApplication.class),
            new String[0],
            null,
            new SQLException("private-password-canary", "28P01"));
    try {
      listener.onApplicationEvent(event);
      listener.onApplicationEvent(event);
      assertThat(appender.list).hasSize(1);
      var logged = appender.list.getFirst();
      assertThat(logged.getFormattedMessage()).isEqualTo("startup_failed");
      assertThat(logged.getThrowableProxy()).isNull();
      assertThat(logged.getKeyValuePairs())
          .anySatisfy(
              pair -> {
                assertThat(pair.key).isEqualTo("reason");
                assertThat(pair.value).isEqualTo("database_authentication");
              });
      assertThat(logged.toString()).doesNotContain("private-password-canary");
    } finally {
      logger.detachAppender(appender);
    }
  }
}
