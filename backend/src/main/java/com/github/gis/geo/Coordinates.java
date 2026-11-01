package com.github.gis.geo;

public final class Coordinates {
    private Coordinates() {}

    public static boolean valid(Double lng, Double lat) {
        return lng != null && lat != null && Double.isFinite(lng) && Double.isFinite(lat)
                && lng >= -180 && lng <= 180 && lat >= -90 && lat <= 90;
    }
}
