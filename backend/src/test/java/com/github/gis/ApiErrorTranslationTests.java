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
