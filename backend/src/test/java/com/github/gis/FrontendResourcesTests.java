package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrontendResourcesTests {

  @Autowired private TestRestTemplate client;

  @Test
  void servesBuiltFrontendAssetsAndApiFromTheSameServer() {
    var page = client.getForEntity("/", String.class);
    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(page.getBody())
        .contains("id=\"app\"", "type=\"module\"")
        .doesNotContain("/src/", "unpkg.com", "cdn.jsdelivr.net");
    // Follow the actual Vite manifest embedded in HTML: a missing resource copy must fail.
    var assets =
        Pattern.compile("(?:src|href)=\"(/assets/[^\"]+\\.(?:js|css))\"")
            .matcher(page.getBody())
            .results()
            .map(match -> match.group(1))
            .toList();
    assertThat(assets)
        .anyMatch(path -> path.endsWith(".js"))
        .anyMatch(path -> path.endsWith(".css"));
    for (var asset : assets) {
      var response = client.getForEntity(asset, String.class);
      assertThat(response.getStatusCode()).as(asset).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).as(asset).isNotBlank().doesNotContain("<!doctype html>");
      assertThat(response.getHeaders().getContentType()).as(asset).isNotNull();
    }
    assertThat(client.getForEntity("/api/v1/osm/points", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void doesNotServeBackendFrontendSourcesLocalConfigurationOrObsoleteScripts() {
    for (String path :
        new String[] {
          "/pom.xml", "/.env", "/AGENTS.md", "/src/main/resources/application.properties",
          "/src/main.ts", "/src/App.svelte", "/package.json", "/vite.config.ts",
          "/constraints.js", "/points.js", "/session.js"
        }) {
      assertThat(client.getForEntity(path, String.class).getStatusCode())
          .as(path)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
  }
}
