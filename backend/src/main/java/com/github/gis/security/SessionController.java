package com.github.gis.security;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
  private final EditingAccess access;

  public SessionController(EditingAccess access) {
    this.access = access;
  }

  @GetMapping("/api/v1/session")
  public ResponseEntity<Map<String, Object>> session(
      Authentication authentication, CsrfToken csrf) {
    boolean canEdit =
        access.enabled()
            && authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(role -> role.getAuthority().equals("ROLE_EDITOR"));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "editing_enabled",
                access.enabled(),
                "can_edit",
                canEdit,
                "csrf_header",
                csrf.getHeaderName(),
                "csrf_token",
                csrf.getToken()));
  }
}
