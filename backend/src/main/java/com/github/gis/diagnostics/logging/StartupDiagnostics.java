package com.github.gis.diagnostics.logging;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/** Registered before run so failures before context creation still produce one safe event. */
public final class StartupDiagnostics implements ApplicationListener<ApplicationEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(StartupDiagnostics.class);
  private final AtomicBoolean failureLogged = new AtomicBoolean();
  private final AtomicBoolean stoppingLogged = new AtomicBoolean();
  private volatile boolean ready;

  @Override
  public void onApplicationEvent(ApplicationEvent event) {
    if (event instanceof ApplicationFailedEvent failed
        && failureLogged.compareAndSet(false, true)) {
      LOG.atError()
          .addKeyValue("event", "startup_failed")
          .addKeyValue("reason", reason(failed.getException()))
          .log("startup_failed");
    } else if (event instanceof ApplicationReadyEvent started
        && started.getApplicationContext() instanceof WebServerApplicationContext context) {
      ready = true;
      LOG.atInfo()
          .addKeyValue("event", "application_ready")
          .addKeyValue("http_port", context.getWebServer().getPort())
          .log("application_ready");
    } else if (event instanceof ContextClosedEvent
        && ready
        && stoppingLogged.compareAndSet(false, true)) {
      LOG.atInfo().addKeyValue("event", "application_stopping").log("application_stopping");
    }
  }

  static String reason(Throwable failure) {
    boolean database = false;
    boolean migration = false;
    boolean invalidConfiguration = false;
    boolean bind = false;
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable current = failure;
        current != null && seen.add(current);
        current = current.getCause()) {
      if (current instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null && state.startsWith("28")) return "database_authentication";
        if (state != null && state.startsWith("08")) database = true;
      }
      String type = current.getClass().getName();
      migration |= type.startsWith("org.flywaydb.");
      database |=
          type.equals("org.springframework.jdbc.CannotGetJdbcConnectionException")
              || type.equals("java.net.ConnectException");
      bind |=
          type.equals("java.net.BindException")
              || type.equals("org.springframework.boot.web.server.PortInUseException");
      invalidConfiguration |=
          type.startsWith("org.springframework.boot.context.properties.bind.")
              || current instanceof IllegalArgumentException;
    }
    if (database) return "database_unavailable";
    if (migration) return "migration_failed";
    if (bind) return "server_bind_failed";
    if (invalidConfiguration) return "invalid_configuration";
    return "startup_failed";
  }
}
