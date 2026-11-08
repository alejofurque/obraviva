package com.obraviva.platform.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cambio de la propia contraseña. Quien la cambia sigue adentro con tokens nuevos, y
 * toda sesión abierta con la contraseña anterior deja de poder renovarse.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
class PasswordChangeIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String email;
    private JsonNode session;

    @BeforeEach
    void setUp() throws Exception {
        email = "clave-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Clave"));
        session = JSON.readTree(login(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions login(String mail, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(mail, password)));
    }

    private ResultActions change(String accessToken, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"%s","newPassword":"%s"}""".formatted(current, next)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}""".formatted(refreshToken)));
    }

    @Test
    @DisplayName("con la contraseña actual correcta se cambia y devuelve tokens que funcionan")
    void changesPasswordAndIssuesTokens() throws Exception {
        String response = change(session.get("accessToken").asString(), PASSWORD, "otraClave456")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode renewed = JSON.readTree(response);

        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, "otraClave456").andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + renewed.get("accessToken").asString()))
                .andExpect(status().isOk());
        refresh(renewed.get("refreshToken").asString()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("las sesiones abiertas con la contraseña anterior ya no se renuevan")
    void previousSessionsCannotRefresh() throws Exception {
        JsonNode otherDevice = JSON.readTree(login(email, PASSWORD)
                .andReturn().getResponse().getContentAsString());

        change(session.get("accessToken").asString(), PASSWORD, "otraClave456").andExpect(status().isOk());

        refresh(otherDevice.get("refreshToken").asString()).andExpect(status().isUnauthorized());
        refresh(session.get("refreshToken").asString()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("una contraseña actual incorrecta es 400 y no 401, para no cerrar la sesión del cliente")
    void wrongCurrentPasswordIsBadRequest() throws Exception {
        change(session.get("accessToken").asString(), "equivocada", "otraClave456")
                .andExpect(status().isBadRequest());

        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("la nueva no puede ser corta ni igual a la actual")
    void weakOrRepeatedPasswordIsRejected() throws Exception {
        change(session.get("accessToken").asString(), PASSWORD, "corta").andExpect(status().isBadRequest());
        change(session.get("accessToken").asString(), PASSWORD, PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sin sesión no se puede cambiar")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"otraClave456"}""".formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }
}
