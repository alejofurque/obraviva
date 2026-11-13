package com.obraviva.platform.auth;

import com.obraviva.platform.support.TestMinio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc // mismo import que en AuthenticationIntegrationTest
@Testcontainers
class RefreshTokenIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        TestMinio.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String EMAIL = "refresh-test@obraviva.test";
    private static final String PASSWORD = "secreto123";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void createUser() {
        if (userRepository.findByEmail(EMAIL).isEmpty()) {
            userRepository.save(new User(EMAIL, passwordEncoder.encode(PASSWORD), "Usuario Refresh"));
        }
    }

    private String login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new LoginRequest(EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String refreshTokenOf(String loginResponse) {
        return JSON.readTree(loginResponse).get("refreshToken").asString();
    }

    private String refreshBody(String refreshToken) {
        return JSON.writeValueAsString(new RefreshRequest(refreshToken));
    }

    @Test
    void refreshRotatesToken() throws Exception {
        String refreshToken = refreshTokenOf(login());

        // Primer uso: OK, devuelve par nuevo
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        // Segundo uso del MISMO token: rechazado (rotación)
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String refreshToken = refreshTokenOf(login());

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithGarbageTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody("token-que-no-existe")))
                .andExpect(status().isUnauthorized());
    }
}