package com.obraviva.platform.auth;

public record LoginResponse(String accessToken, String refreshToken) {
}