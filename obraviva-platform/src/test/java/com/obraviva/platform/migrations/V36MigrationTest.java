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
 * La V36 sobre imágenes que ya estaban: la portada y el render del frente de una casa, el
 * render de una comparación y la portada del desarrollo entran a la cola de versiones
 * livianas, con la organización de su dueño; un SVG queda salteado y una subida que nunca se
 * confirmó (la clave pendiente) no entra.
 *
 * <p>Sin Spring, como {@link V34MigrationTest}: Flyway hasta la V35, datos a mano, y la V36.
 */
@Testcontainers
class V36MigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DEV = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID COMPARISON = UUID.randomUUID();

    @Test
    @DisplayName("las portadas y los renders cargados quedan pendientes, el SVG salteado y lo no confirmado afuera")
    void queuesExistingImages() throws Exception {
        flyway("35").migrate();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("insert into organizations (id, name) values ('%s', 'Org')".formatted(ORG));
            sql.execute("insert into users (id, email, password_hash, full_name) values ('%s', 'u@obraviva.test', 'x', 'U')".formatted(USER));
            sql.execute(("insert into developments (id, organization_id, name, cover_storage_key, cover_pending_storage_key) "
                    + "values ('%s', '%s', 'Barrio', 'dev/cover.jpg', 'dev/cover-pendiente.jpg')").formatted(DEV, ORG));
            sql.execute(("insert into units (id, organization_id, development_id, code, cover_storage_key, front_render_storage_key) "
                    + "values ('%s', '%s', '%s', 'Casa 1', 'casa/cover.svg', 'casa/front-render.png')").formatted(UNIT, ORG, DEV));
            sql.execute(("insert into project_comparisons (id, organization_id, development_id, title, created_by, render_storage_key) "
                    + "values ('%s', '%s', '%s', 'Frente', '%s', 'comparacion/render.png')").formatted(COMPARISON, ORG, DEV, USER));
        }

        flyway("36").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            assertThat(status(sql, "dev/cover.jpg")).isEqualTo("PENDING");
            assertThat(status(sql, "casa/front-render.png")).isEqualTo("PENDING");
            assertThat(status(sql, "comparacion/render.png")).isEqualTo("PENDING");
            assertThat(status(sql, "casa/cover.svg")).isEqualTo("SKIPPED");
            assertThat(status(sql, "dev/cover-pendiente.jpg")).isNull();
            try (ResultSet rows = sql.executeQuery(
                    "select count(*), count(*) filter (where organization_id = '%s' and attempts = 0 and thumb_storage_key is null) from image_variants"
                            .formatted(ORG))) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(4);
                assertThat(rows.getInt(2)).isEqualTo(4);
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

    private static String status(Statement sql, String storageKey) throws SQLException {
        try (ResultSet rows = sql.executeQuery("select status from image_variants where storage_key = '%s'".formatted(storageKey))) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
