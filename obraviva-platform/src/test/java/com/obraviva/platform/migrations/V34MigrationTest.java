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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La V34 sobre fotos que ya estaban: lo publicado entra a la cola de versiones livianas, un
 * SVG queda fuera (no se achica) y una subida que nunca se confirmó no se toca.
 *
 * <p>Sin Spring, como {@link V30MigrationTest}: Flyway hasta la V33, datos a mano, y la V34.
 */
@Testcontainers
class V34MigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DEV = UUID.randomUUID();

    @Test
    @DisplayName("lo publicado queda pendiente de versiones, el SVG salteado y lo no confirmado sin estado")
    void queuesPublishedMedia() throws Exception {
        flyway("33").migrate();
        UUID foto;
        UUID video;
        UUID render;
        UUID pendiente;
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("insert into organizations (id, name) values ('%s', 'Org')".formatted(ORG));
            sql.execute("insert into users (id, email, password_hash, full_name) values ('%s', 'u@obraviva.test', 'x', 'U')".formatted(USER));
            sql.execute("insert into developments (id, organization_id, name) values ('%s', '%s', 'Barrio')".formatted(DEV, ORG));
            foto = insertMedia(sql, "image/png", "PHOTO", "AVAILABLE");
            video = insertMedia(sql, "video/mp4", "VIDEO", "AVAILABLE");
            render = insertMedia(sql, "image/svg+xml", "PHOTO", "AVAILABLE");
            pendiente = insertMedia(sql, "image/jpeg", "PHOTO", "PENDING");
        }

        flyway("34").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            assertThat(status(sql, foto)).isEqualTo("PENDING");
            assertThat(status(sql, video)).isEqualTo("PENDING");
            assertThat(status(sql, render)).isEqualTo("SKIPPED");
            assertThat(status(sql, pendiente)).isNull();
            try (ResultSet rows = sql.executeQuery(
                    "select count(*) from media_assets where thumb_storage_key is not null or screen_storage_key is not null or variants_attempts <> 0")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
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

    private static UUID insertMedia(Statement sql, String contentType, String kind, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        sql.execute(("insert into media_assets (id, organization_id, development_id, storage_key, original_filename, "
                + "content_type, kind, status, created_by) values ('%s', '%s', '%s', '%s', 'f', '%s', '%s', '%s', '%s')")
                .formatted(id, ORG, DEV, "k/" + id, contentType, kind, status, USER));
        return id;
    }

    private static String status(Statement sql, UUID media) throws SQLException {
        try (ResultSet rows = sql.executeQuery("select variants_status from media_assets where id = '%s'".formatted(media))) {
            rows.next();
            return rows.getString(1);
        }
    }
}
