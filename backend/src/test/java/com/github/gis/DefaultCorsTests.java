package com.github.gis;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.gis.controllers.OSMController;
import com.github.gis.points.PointService;
import com.github.gis.security.EditingAccess;
import com.github.gis.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OSMController.class, properties = "CORS_ALLOWED_ORIGINS=")
@Import({SecurityConfiguration.class, EditingAccess.class})
class DefaultCorsTests {
  @Autowired MockMvc mvc;
  // CORS is the boundary under test; no spatial queries or persistence are needed.
  @MockitoBean JdbcTemplate jdbc;
  @MockitoBean PointService points;

  @Test
  void sameOriginAndNonBrowserRequestsWorkWithoutCorsHeaders() throws Exception {
    mvc.perform(get("/api/v1/osm/points"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    mvc.perform(get("/api/v1/osm/points").header("Origin", "http://localhost"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void formerLiveServerOriginHasNoImplicitPermission() throws Exception {
    mvc.perform(get("/api/v1/osm/points").header("Origin", "http://localhost:5500"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    mvc.perform(
            options("/api/v1/osm/points/add")
                .header("Origin", "http://localhost:5500")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
