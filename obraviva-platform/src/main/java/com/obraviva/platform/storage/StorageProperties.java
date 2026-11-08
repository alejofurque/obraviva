package com.obraviva.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "obraviva.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        Duration uploadUrlTtl,
        Duration downloadUrlTtl) {
}