package com.github.gis.config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ApiCorsConfiguration implements WebMvcConfigurer {
  private final List<String> origins;

  public ApiCorsConfiguration(@Value("${app.cors.allowed-origins:}") String configuredOrigins) {
    origins =
        Arrays.stream(configuredOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList();
    origins.forEach(ApiCorsConfiguration::validateOrigin);
  }

  private static void validateOrigin(String origin) {
    URI uri;
    try {
      uri = URI.create(origin);
    } catch (IllegalArgumentException exception) {
      throw invalidOrigins();
    }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || !uri.getRawPath().isEmpty()
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPort() == 0
        || uri.getPort() > 65535
        || uri.getRawAuthority().endsWith(":")) {
      throw invalidOrigins();
    }
  }

  private static IllegalArgumentException invalidOrigins() {
    // Do not echo a malformed URL: it may contain accidentally supplied credentials.
    return new IllegalArgumentException(
        "CORS_ALLOWED_ORIGINS must contain only exact HTTP(S) origins without paths or credentials");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (!origins.isEmpty()) {
      registry
          .addMapping("/api/v1/osm/**")
          .allowedOrigins(origins.toArray(String[]::new))
          .allowedMethods("GET", "POST", "DELETE")
          .allowedHeaders("Content-Type")
          .exposedHeaders("X-Request-ID")
          .allowCredentials(false);
    }
  }
}
