package com.github.gis.geo;

import com.github.gis.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;

/** Validated WGS84 viewport; west greater than east denotes a dateline crossing. */
public record SpatialQuery(double west, double south, double east, double north, int zoom) {
  static final double MERCATOR_LATITUDE = 85.0511287798066;

  public static SpatialQuery world() {
    return new SpatialQuery(-180, -90, 180, 90, 22);
  }

  public static SpatialQuery parse(MultiValueMap<String, String> parameters) {
    try {
      if (parameters.getOrDefault("bbox", java.util.List.of()).size() > 1
          || parameters.getOrDefault("zoom", java.util.List.of()).size() > 1)
        throw new IllegalArgumentException();
      int zoom = 22;
      if (parameters.containsKey("zoom")) {
        String value = parameters.getFirst("zoom");
        if (value == null || !value.matches("[0-9]{1,2}")) throw new IllegalArgumentException();
        zoom = Integer.parseInt(value);
        if (zoom > 22) throw new IllegalArgumentException();
      }
      String bbox = parameters.getFirst("bbox");
      if (bbox == null && !parameters.containsKey("bbox"))
        return new SpatialQuery(-180, -90, 180, 90, zoom);
      String[] values = bbox.split(",", -1);
      if (values.length != 4) throw new IllegalArgumentException();
      for (String value : values)
        if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
          throw new IllegalArgumentException();
      double west = Double.parseDouble(values[0]),
          south = Double.parseDouble(values[1]),
          east = Double.parseDouble(values[2]),
          north = Double.parseDouble(values[3]);
      if (!Coordinates.valid(west, south)
          || !Coordinates.valid(east, north)
          || south >= north
          || west == east
          || west == 180 && east == -180) throw new IllegalArgumentException();
      return new SpatialQuery(west, south, east, north, zoom);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "invalid_spatial_query",
          "Требуются bbox=west,south,east,north в WGS84 и целый zoom от 0 до 22; параметры не должны повторяться.");
    }
  }
}
