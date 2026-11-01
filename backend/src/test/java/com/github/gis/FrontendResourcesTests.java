package com.github.gis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrontendResourcesTests {

    @Autowired
    private TestRestTemplate client;

    @Test
    void servesFrontendAndApiFromTheSameServer() {
        var page = client.getForEntity("/", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("id=\"map\"");

        for (String asset : new String[]{
                "index.css", "index.js", "points.js", "roads.js", "polygons.js",
                "distance.js", "constraints.js", "session.js", "add-point.js", "delete-point.js"
        }) {
            var response = client.getForEntity("/" + asset, String.class);
            assertThat(response.getStatusCode()).as(asset).isEqualTo(HttpStatus.OK);
        }

        var config = client.getForEntity("/constraints.js", String.class);
        assertThat(config.getBody()).contains("\"/api/v1/osm\"").doesNotContain("localhost");
        assertThat(client.getForEntity("/api/v1/osm/points", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void doesNotServeBackendSourcesOrLocalConfiguration() {
        for (String path : new String[]{
                "/pom.xml", "/.env", "/AGENTS.md", "/src/main/resources/application.properties"
        }) {
            assertThat(client.getForEntity(path, String.class).getStatusCode())
                    .as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
