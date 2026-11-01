package com.github.gis.geo;

import com.github.gis.api.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;

public record DistanceRequest(List<List<Double>> points) {
  public void validate() {
    if (points == null || points.size() < 2 || points.size() > 1000) throw invalid();
    for (var pair : points) {
      if (pair == null || pair.size() != 2 || !Coordinates.valid(pair.get(0), pair.get(1)))
        throw invalid();
    }
  }

  private ApiException invalid() {
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        "invalid_distance_request",
        "Требуется 2–1000 пар [lng,lat]: конечные числа, lng от −180 до 180, lat от −90 до 90.");
  }
}
