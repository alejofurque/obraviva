package com.obraviva.platform.migrations;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La V30 sobre datos de antes: marca como galería exactamente lo que hasta la V29 se
 * mostraba como galería (foto disponible, de una casa y sin avance) y nada más, y marca
 * como frente el sector que se llama "Frente" sin distinguir mayúsculas, uno por casa.
 *
 * <p>Sin Spring: Flyway migra hasta la V29, se cargan los casos a mano, y después aplica
 * la V30. Es la única forma de probar un UPDATE de migración sobre datos anteriores.
 */
@Testcontainers
class V30MigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DEV = UUID.randomUUID();
    private static final UUID CASA1 = UUID.randomUUID();
    private static final UUID CASA2 = UUID.randomUUID();
    private static final UUID CASA3 = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    @Test
    @DisplayName("galería: sólo foto disponible de una casa sin avance; frente: el sector \"Frente\", uno por casa")
    void marksGalleryAndFront() throws Exception {
        flyway("29").migrate();
        Map<String, UUID> media = new HashMap<>();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("insert into organizations (id, name) values ('%s', 'Org')".formatted(ORG));
            sql.execute("insert into users (id, email, password_hash, full_name) values ('%s', 'u@obraviva.test', 'x', 'U')".formatted(USER));
            sql.execute("insert into developments (id, organization_id, name) values ('%s', '%s', 'Barrio')".formatted(DEV, ORG));
            for (UUID unit : new UUID[] {CASA1, CASA2, CASA3}) {
                sql.execute("insert into units (id, organization_id, development_id, code) values ('%s', '%s', '%s', '%s')"
                        .formatted(unit, ORG, DEV, "Casa " + unit.toString().substring(0, 4)));
            }
            sql.execute(("insert into progress_entries (id, organization_id, development_id, entry_date, description, source, version, root_id, created_by) "
                    + "values ('%s', '%s', '%s', '2026-09-01', 'Avance', 'MANUAL', 1, '%s', '%s')").formatted(ENTRY, ORG, DEV, ENTRY, USER));

            media.put("galeria", insertMedia(sql, CASA1, null, "PHOTO", "AVAILABLE"));
            media.put("avance", insertMedia(sql, CASA1, ENTRY, "PHOTO", "AVAILABLE"));
            media.put("pendiente", insertMedia(sql, CASA1, null, "PHOTO", "PENDING"));
            media.put("comun", insertMedia(sql, null, null, "PHOTO", "AVAILABLE"));
            media.put("video", insertMedia(sql, CASA1, null, "VIDEO", "AVAILABLE"));

            insertSector(sql, CASA1, "frente", 2);
            insertSector(sql, CASA1, "Living", 1);
            insertSector(sql, CASA2, " FRENTE ", 1);
            insertSector(sql, CASA2, "Frente", 2);
            insertSector(sql, CASA3, "Frente de la casa", 1);
        }

        flyway("30").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            assertThat(showcase(sql, media.get("galeria"))).isTrue();
            assertThat(showcase(sql, media.get("avance"))).isFalse();
            assertThat(showcase(sql, media.get("pendiente"))).isFalse();
            assertThat(showcase(sql, media.get("comun"))).isFalse();
            assertThat(showcase(sql, media.get("video"))).isFalse();
            assertThat(count(sql, "select count(*) from media_assets where showcase")).isEqualTo(1);
            assertThat(count(sql, "select count(*) from media_assets where taken_on is not null")).isZero();

            assertThat(front(sql, CASA1)).isEqualTo("frente");
            assertThat(front(sql, CASA2)).isEqualTo(" FRENTE ");
            assertThat(count(sql, "select count(*) from unit_capture_sectors where is_front and unit_id = '%s'".formatted(CASA3))).isZero();
            // A lo sumo un frente por casa: lo garantiza el índice único parcial.
            assertThatThrownBy(() -> sql.execute(
                    "update unit_capture_sectors set is_front = true where unit_id = '%s' and name = 'Living'".formatted(CASA1)))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static UUID insertMedia(Statement sql, UUID unit, UUID entry, String kind, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        sql.execute(("insert into media_assets (id, organization_id, development_id, progress_entry_id, storage_key, original_filename, "
                + "content_type, kind, status, created_by, unit_id) values ('%s', '%s', '%s', %s, '%s', 'f', '%s', '%s', '%s', '%s', %s)")
                .formatted(id, ORG, DEV, entry == null ? "null" : "'" + entry + "'", "k/" + id,
                        kind.equals("PHOTO") ? "image/jpeg" : "video/mp4", kind, status, USER,
                        unit == null ? "null" : "'" + unit + "'"));
        return id;
    }

    private static void insertSector(Statement sql, UUID unit, String name, int order) throws SQLException {
        sql.execute("insert into unit_capture_sectors (id, organization_id, unit_id, name, order_index) values ('%s', '%s', '%s', '%s', %d)"
                .formatted(UUID.randomUUID(), ORG, unit, name, order));
    }

    private static boolean showcase(Statement sql, UUID media) throws SQLException {
        try (ResultSet rows = sql.executeQuery("select showcase from media_assets where id = '%s'".formatted(media))) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static String front(Statement sql, UUID unit) throws SQLException {
        try (ResultSet rows = sql.executeQuery("select name from unit_capture_sectors where is_front and unit_id = '%s'".formatted(unit))) {
            assertThat(rows.next()).isTrue();
            String name = rows.getString(1);
            assertThat(rows.next()).isFalse();
            return name;
        }
    }

    private static long count(Statement sql, String query) throws SQLException {
        try (ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
