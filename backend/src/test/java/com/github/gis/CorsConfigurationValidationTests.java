package com.github.gis;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.github.gis.config.ApiCorsConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorsConfigurationValidationTests {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "*",
        "null",
        "https://*.example.test",
        "https://example.test/path",
        "https://example.test/",
        "https://example.test?query",
        "https://example.test#fragment",
        "http://user:credential@example.test",
        "file:///tmp/viewer",
        "localhost:5173",
        "http://example.test:0",
        "http://example.test:65536",
        "http://example.test:",
        "http://localhost:5173, *"
      })
  void refusesOriginsThatCouldBroadenOrMisrepresentTheAllowlist(String origins) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ApiCorsConfiguration(origins))
        .withMessage(
            "CORS_ALLOWED_ORIGINS must contain only exact HTTP(S) origins without paths or credentials");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "  ",
        " , ",
        "http://localhost:5173, https://viewer.example.test",
        "http://127.0.0.1:5500",
        "http://[::1]:5173"
      })
  void acceptsDisabledCorsAndExplicitWebOrigins(String origins) {
    assertThatNoException().isThrownBy(() -> new ApiCorsConfiguration(origins));
  }
}
