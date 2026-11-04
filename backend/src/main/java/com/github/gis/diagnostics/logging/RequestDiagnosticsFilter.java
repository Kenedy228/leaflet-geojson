package com.github.gis.diagnostics.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Outermost application filter: correlation also covers firewall, CORS and security refusals. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestDiagnosticsFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RequestDiagnosticsFilter.class);
  private static final Set<String> METHODS =
      Set.of("GET", "HEAD", "POST", "DELETE", "PUT", "PATCH", "OPTIONS", "TRACE");
  private static final Map<String, String> ROUTES =
      Map.ofEntries(
          Map.entry("/", "index"),
          Map.entry("/api/v1/osm/points", "points"),
          Map.entry("/api/v1/osm/points/add", "points_create"),
          Map.entry("/api/v1/osm/points/{id}", "points_delete"),
          Map.entry("/api/v1/osm/roads", "roads"),
          Map.entry("/api/v1/osm/polygons", "polygons"),
          Map.entry("/api/v1/osm/distances", "distances"),
          Map.entry("/api/v1/session", "session"),
          Map.entry("/login", "login"),
          Map.entry("/logout", "logout"),
          Map.entry("/actuator/health", "health"),
          Map.entry("/actuator/health/liveness", "liveness"),
          Map.entry("/actuator/health/readiness", "readiness"));

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String previous = MDC.get("request_id");
    String requestId = UUID.randomUUID().toString();
    long start = System.nanoTime();
    boolean failed = true;
    MDC.put("request_id", requestId);
    response.setHeader("X-Request-ID", requestId);
    try {
      chain.doFilter(request, response);
      failed = false;
    } finally {
      try {
        int status = failed ? 500 : response.getStatus();
        var log = status >= 500 ? LOG.atError() : status >= 400 ? LOG.atWarn() : LOG.atInfo();
        log.addKeyValue("event", "request_completed")
            .addKeyValue("route", route(request))
            .addKeyValue(
                "method", METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER")
            .addKeyValue("status", status)
            .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
            .log("request_completed");
      } finally {
        if (previous == null) MDC.remove("request_id");
        else MDC.put("request_id", previous);
      }
    }
  }

  private static String route(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    // Only fixed labels leave the process. Unknown paths, identifiers and query strings never do.
    String label = pattern instanceof String value ? ROUTES.get(value) : null;
    return label != null ? label : ROUTES.getOrDefault(request.getRequestURI(), "other");
  }
}
