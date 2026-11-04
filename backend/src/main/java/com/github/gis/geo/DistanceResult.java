package com.github.gis.geo;

import java.util.List;

public record DistanceResult(List<Segment> lines, double totalDistance) {
  public record Segment(List<Double> from, List<Double> to, double distance) {}
}
