package com.github.gis;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.gis.controllers.OSMController;
import com.github.gis.geo.SpatialService;
import com.github.gis.points.PointService;
import com.github.gis.security.EditingAccess;
import com.github.gis.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = OSMController.class,
    properties = "CORS_ALLOWED_ORIGINS= http://localhost:5173 , https://viewer.example.test ")
@Import({SecurityConfiguration.class, EditingAccess.class})
class ConfiguredCorsTests {
  @Autowired MockMvc mvc;
  @MockitoBean SpatialService spatial;
  @MockitoBean PointService points;

  @Test
  void allowsOnlyExplicitOriginsWithoutCredentials() throws Exception {
    for (String origin : new String[] {"http://localhost:5173", "https://viewer.example.test"}) {
      mvc.perform(get("/api/v1/osm/points").header("Origin", origin))
          .andExpect(status().isOk())
          .andExpect(content().json("[]"))
          .andExpect(header().string("Access-Control-Allow-Origin", origin))
          .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
    for (String origin :
        new String[] {
          "http://localhost:5500",
          "http://localhost:5174",
          "https://viewer.example.test.evil.test",
          "null"
        }) {
      mvc.perform(get("/api/v1/osm/points").header("Origin", origin))
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
  }

  @Test
  void permitsJsonPostAndDeletePreflights() throws Exception {
    for (String method : new String[] {"POST", "DELETE"}) {
      String path =
          method.equals("POST")
              ? "/api/v1/osm/points/add"
              : "/api/v1/osm/points/user:00000000-0000-0000-0000-000000000001";
      mvc.perform(
              options(path)
                  .header("Origin", "http://localhost:5173")
                  .header("Access-Control-Request-Method", method)
                  .header("Access-Control-Request-Headers", "content-type"))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
          .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,DELETE"))
          .andExpect(header().string("Access-Control-Allow-Headers", "content-type"))
          .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
  }

  @Test
  void rejectsUnexpectedMethodAndHeader() throws Exception {
    mvc.perform(
            options("/api/v1/osm/points/add")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH"))
        .andExpect(status().isForbidden());
    mvc.perform(
            options("/api/v1/osm/points/add")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Untrusted"))
        .andExpect(status().isForbidden());
  }

  @Test
  void doesNotGrantCrossOriginAccessToStaticFiles() throws Exception {
    mvc.perform(get("/").header("Origin", "http://localhost:5173"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
