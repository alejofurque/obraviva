package com.obraviva.platform.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // API stateless con JWT: sin cookies de sesión, CSRF no aplica
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Vista pública de una unidad: la credencial es el token del
                        // enlace, que el propio endpoint resuelve. Sólo GET: nada bajo
                        // /api/public/ debe poder modificar estado sin sesión.
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        // Spring reenvía internamente a /error para armar el cuerpo de
                        // cualquier respuesta de error. Sin este permiso, el 404 de un
                        // enlace revocado llega al navegador como 401: Security rechaza
                        // el reenvío porque la petición es anónima. Bug real, y uno que
                        // los tests de MockMvc no ven, porque MockMvc no ejecuta ese
                        // reenvío. No expone nada: el cuerpo de error no incluye traza
                        // (include-stacktrace viene desactivado por omisión).
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Delegante estándar: bcrypt por defecto, hashes prefijados ({bcrypt}...)
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private SecretKey secretKey(String base64Secret) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Secret);
        return new SecretKeySpec(keyBytes, "HmacSHA512");
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${obraviva.security.jwt.secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(secret)));
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${obraviva.security.jwt.secret}") String secret) {
        // El algoritmo se declara explícitamente en encoder y decoder: un encoder
        // HS512 contra un decoder que asume HS256 por omisión ya causó un bug real.
        return NimbusJwtDecoder.withSecretKey(secretKey(secret))
                .macAlgorithm(MacAlgorithm.HS512)
                .build();
    }
}