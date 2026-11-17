package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlaceCatalog implements PlaceCatalog {
    private static final RowMapper<Place> PLACE = (row, ignored) -> new Place(
            row.getString("id"), row.getString("name"), row.getString("region"), row.getString("address"),
            new Coordinate(row.getDouble("latitude"), row.getDouble("longitude")), row.getString("thumbnail_url"),
            Category.valueOf(row.getString("category")), strings(row, "hashtags"), strings(row, "photos"),
            row.getString("introduction"), row.getString("parking_info"), row.getString("recommended_visit_time"),
            strings(row, "main_roads"));
    private final JdbcClient jdbc;

    JdbcPlaceCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Place> findByCategory(Category category) {
        return jdbc.sql("SELECT * FROM places WHERE category = :category ORDER BY id")
                .param("category", category.name()).query(PLACE).list();
    }

    @Override
    public List<Place> findRecommendationsByCategory(Category category) {
        return jdbc.sql("""
                SELECT * FROM places WHERE category = :category AND recommendation_order IS NOT NULL
                ORDER BY recommendation_order, id
                """).param("category", category.name()).query(PLACE).list();
    }

    @Override
    public List<Place> findPopularDestinations() {
        return jdbc.sql("SELECT * FROM places WHERE popular_order IS NOT NULL ORDER BY popular_order, id")
                .query(PLACE).list();
    }

    @Override
    public Optional<Place> findById(String placeId) {
        return jdbc.sql("SELECT * FROM places WHERE id = :id").param("id", placeId).query(PLACE).optional();
    }

    private static List<String> strings(ResultSet row, String column) throws SQLException {
        var array = row.getArray(column);
        try {
            return Arrays.stream((Object[]) array.getArray()).map(String.class::cast).toList();
        } finally {
            array.free();
        }
    }
}
