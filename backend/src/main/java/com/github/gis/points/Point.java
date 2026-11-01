package com.github.gis.points;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Point(String id, String source, @JsonProperty("osm_id") String osmId,
                    String name, String geojson) {
}
