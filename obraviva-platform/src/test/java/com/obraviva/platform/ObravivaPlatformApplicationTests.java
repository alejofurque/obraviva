package com.obraviva.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifica que el contexto completo de la aplicación levante.
 *
 * <p>Es el único test que ejercita todo el cableado de beans a la vez, así que es
 * el que detecta una configuración rota que los tests de módulo no ven.
 *
 * <p>Usa Testcontainers como el resto de la suite. Antes se conectaba al Postgres
 * de `docker compose`, lo que lo hacía fallar por motivos ajenos al código: un
 * contenedor de otro proyecto ocupando el puerto 5432 bastaba para romperlo, con un
 * error de autenticación que aparentaba ser un problema de credenciales.
 *
 * <p>El inicializador del bucket se desactiva: verificar el cableado no requiere
 * MinIO corriendo.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class ObravivaPlatformApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void contextLoads() {
    }
}