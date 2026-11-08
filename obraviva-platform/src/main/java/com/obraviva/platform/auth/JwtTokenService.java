package com.obraviva.platform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final Duration accessTokenTtl;

    public JwtTokenService(
            JwtEncoder jwtEncoder,
            @Value("${obraviva.security.jwt.access-token-ttl}") Duration accessTokenTtl) {
        this.jwtEncoder = jwtEncoder;
        this.accessTokenTtl = accessTokenTtl;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("obraviva")
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenTtl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}