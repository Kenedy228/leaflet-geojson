package com.github.gis.geo;

import com.github.gis.api.ApiException;
import com.github.gis.points.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.stereotype.Service;

@Service
public class SpatialService {
  private final JdbcTemplate jdbc;

  public SpatialService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Point> points(SpatialQuery viewport) {
    return rows(query("points", viewport)).stream()
        .map(
            row ->
                new Point(
                    (String) row.get("id"),
                    (String) row.get("source"),
                    (String) row.get("osm_id"),
                    (String) row.get("name"),
                    (String) row.get("geojson")))
        .toList();
  }

  public List<Map<String, Object>> layer(String layer, SpatialQuery viewport) {
    return rows(query(layer, viewport));
  }

  /** The actual parameterized production query, also used by the isolated benchmark. */
  static record Sql(String sql, List<Object> parameters) {}

  static Sql query(String layer, SpatialQuery viewport) {
    var parameters = new ArrayList<Object>();
    String userRegion = region(viewport, 4326, parameters);
    String osmRegion = region(viewport, 3857, parameters);
    String extra = "";
    if (viewport.west() > viewport.east()) {
      extra =
          ","
              + region(
                  new SpatialQuery(
                      viewport.west(), viewport.south(), 180, viewport.north(), viewport.zoom()),
                  4326,
                  parameters)
              + " AS user_first,"
              + region(
                  new SpatialQuery(
                      -180, viewport.south(), viewport.east(), viewport.north(), viewport.zoom()),
                  4326,
                  parameters)
              + " AS user_second,"
              + region(
                  new SpatialQuery(
                      viewport.west(), viewport.south(), 180, viewport.north(), viewport.zoom()),
                  3857,
                  parameters)
              + " AS osm_first,"
              + region(
                  new SpatialQuery(
                      -180, viewport.south(), viewport.east(), viewport.north(), viewport.zoom()),
                  3857,
                  parameters)
              + " AS osm_second";
    }
    String envelopes =
        "WITH bounds AS MATERIALIZED (SELECT "
            + userRegion
            + " AS user_region,"
            + osmRegion
            + " AS osm_region"
            + extra
            + "), ";
    String osmFilter = filter("way", "osm", viewport.west() > viewport.east());
    String userFilter = filter("geom", "user", viewport.west() > viewport.east());
    if (layer.equals("points")) {
      String candidates =
          """
          candidates AS MATERIALIZED (
            SELECT * FROM (
              SELECT 'osm:' || osm_id::text AS id, 'osm' AS source,
                     osm_id::text AS osm_id, name, ST_Transform(way,4326) AS geom
              FROM public.planet_osm_point, bounds
              WHERE name IS NOT NULL AND %s LIMIT 501
            ) osm
            UNION ALL
            SELECT * FROM (
              SELECT 'user:' || id::text, 'user', NULL::text, name, geom
              FROM app.user_points, bounds WHERE %s LIMIT 501
            ) users LIMIT 501
          ),
          stats AS (SELECT count(*) AS feature_count FROM candidates)
          SELECT stats.feature_count, false AS geometry_overflow,
                 c.id, c.source, c.osm_id, c.name,
                 CASE WHEN stats.feature_count <= 500 THEN ST_AsGeoJSON(c.geom) END AS geojson
          FROM stats LEFT JOIN candidates c ON stats.feature_count <= 500 ORDER BY c.id
          """
              .formatted(osmFilter, userFilter);
      return new Sql(envelopes + candidates, parameters);
    }
    boolean roads = layer.equals("roads");
    if (!roads && !layer.equals("polygons")) throw new IllegalArgumentException("Unknown layer");
    String attributes =
        roads
            ? "osm_id,name,highway"
            : "osm_id,name,tags->'leisure' AS leisure_type,tags->'landuse' AS landuse_type";
    String columns =
        roads ? "c.osm_id,c.name,c.highway" : "c.osm_id,c.name,c.leisure_type,c.landuse_type";
    parameters.add(Math.max(0.05, 156543.03392804097 / Math.pow(2, viewport.zoom())));
    String projectedAttributes =
        roads ? "osm_id,name,highway" : "osm_id,name,leisure_type,landuse_type";
    String sql =
        envelopes
            + """
        candidates AS MATERIALIZED (
          SELECT %s, way, osm_region FROM public.%s, bounds
          WHERE %s IS NOT NULL AND %s LIMIT 501
        ), counts AS MATERIALIZED (
          SELECT count(*) AS feature_count FROM candidates
        ), clipped AS MATERIALIZED (
          SELECT %s,
                 ST_SimplifyPreserveTopology(ST_CollectionExtract(
                   CASE WHEN ST_CoveredBy(way,osm_region) THEN way
                        ELSE ST_Intersection(way,osm_region) END, %s), ?) AS geom
          FROM candidates, counts WHERE feature_count <= 500
        ), visible AS MATERIALIZED (
          SELECT *, ST_NPoints(geom) AS vertices FROM clipped WHERE NOT ST_IsEmpty(geom)
        ), stats AS MATERIALIZED (
          SELECT feature_count,
                 COALESCE((SELECT max(vertices)>5000 OR sum(vertices)>20000 FROM visible),false)
                   AS geometry_overflow FROM counts
        )
        SELECT stats.feature_count, stats.geometry_overflow, %s,
               CASE WHEN NOT stats.geometry_overflow
                    THEN ST_AsGeoJSON(ST_Transform(c.geom,4326)) END AS geojson
        FROM stats LEFT JOIN visible c ON NOT stats.geometry_overflow ORDER BY c.osm_id
        """
                .formatted(
                    attributes,
                    roads ? "planet_osm_roads" : "planet_osm_polygon",
                    roads ? "highway" : "name",
                    osmFilter,
                    projectedAttributes,
                    roads ? 2 : 3,
                    columns);
    return new Sql(sql, parameters);
  }

  private static String filter(String geometry, String prefix, boolean wrapped) {
    if (!wrapped)
      return "("
          + geometry
          + " && "
          + prefix
          + "_region AND ST_Intersects("
          + geometry
          + ","
          + prefix
          + "_region))";
    return "(("
        + geometry
        + " && "
        + prefix
        + "_first AND ST_Intersects("
        + geometry
        + ","
        + prefix
        + "_first)) OR ("
        + geometry
        + " && "
        + prefix
        + "_second AND ST_Intersects("
        + geometry
        + ","
        + prefix
        + "_second)))";
  }

  private static String region(SpatialQuery q, int srid, List<Object> parameters) {
    double south = q.south(), north = q.north();
    if (srid == 3857) {
      south = Math.max(south, -SpatialQuery.MERCATOR_LATITUDE);
      north = Math.min(north, SpatialQuery.MERCATOR_LATITUDE);
      if (south >= north) return "ST_GeomFromText('POLYGON EMPTY',3857)";
    }
    if (q.west() > q.east())
      return "ST_Union("
          + envelope(q.west(), south, 180, north, srid, parameters)
          + ","
          + envelope(-180, south, q.east(), north, srid, parameters)
          + ")";
    return envelope(q.west(), south, q.east(), north, srid, parameters);
  }

  private static String envelope(
      double west, double south, double east, double north, int srid, List<Object> parameters) {
    parameters.add(west);
    parameters.add(south);
    parameters.add(east);
    parameters.add(north);
    String sql = "ST_MakeEnvelope(?,?,?,?,4326)";
    return srid == 3857 ? "ST_Transform(" + sql + ",3857)" : sql;
  }

  private List<Map<String, Object>> rows(Sql query) {
    List<Map<String, Object>> rows = execute(query);
    if (((Number) rows.getFirst().get("feature_count")).intValue() > 500)
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "spatial_limit_exceeded",
          "Слишком много объектов. Приблизьте карту и повторите запрос.");
    if (Boolean.TRUE.equals(rows.getFirst().get("geometry_overflow")))
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "spatial_geometry_limit_exceeded",
          "Слишком сложная геометрия. Приблизьте карту и повторите запрос.");
    var result = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      if (row.get("geojson") == null) continue;
      row.remove("feature_count");
      row.remove("geometry_overflow");
      result.add(row);
    }
    return result;
  }

  private List<Map<String, Object>> execute(Sql query) {
    try {
      PreparedStatementCreator creator =
          connection -> {
            var statement = connection.prepareStatement(query.sql());
            statement.setQueryTimeout(2);
            for (int i = 0; i < query.parameters().size(); i++)
              statement.setObject(i + 1, query.parameters().get(i));
            return statement;
          };
      return jdbc.query(creator, new org.springframework.jdbc.core.ColumnMapRowMapper());
    } catch (QueryTimeoutException exception) {
      throw timeout();
    }
  }

  static ApiException timeout() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "spatial_query_timeout",
        "Запрос превысил время ожидания. Уменьшите область карты или число точек и повторите запрос.");
  }

  public DistanceResult distance(DistanceRequest request) {
    request.validate();
    List<List<Double>> coordinates = request.points();
    var parameters = new ArrayList<Object>();
    String values =
        String.join(",", java.util.Collections.nCopies(coordinates.size(), "(?, ?, ?)"));
    for (int i = 0; i < coordinates.size(); i++) {
      parameters.add(i);
      parameters.add(coordinates.get(i).get(0));
      parameters.add(coordinates.get(i).get(1));
    }
    String sql =
        "WITH coordinates(ord,lng,lat) AS (VALUES "
            + values
            + "), ordered AS (SELECT ord,ST_SetSRID(ST_MakePoint(lng::double precision,lat::double precision),4326)::geography AS point FROM coordinates), segments AS (SELECT ord,point,lead(point) OVER (ORDER BY ord) AS next FROM ordered) SELECT ord,ST_Distance(point,next) AS distance FROM segments WHERE next IS NOT NULL ORDER BY ord";
    var lines = new ArrayList<DistanceResult.Segment>();
    double total = 0;
    for (var row : execute(new Sql(sql, parameters))) {
      int index = ((Number) row.get("ord")).intValue();
      double metres = ((Number) row.get("distance")).doubleValue();
      lines.add(
          new DistanceResult.Segment(coordinates.get(index), coordinates.get(index + 1), metres));
      total += metres;
    }
    return new DistanceResult(lines, total);
  }
}
