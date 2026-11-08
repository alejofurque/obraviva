package com.obraviva.platform.media;

import java.util.UUID;

/**
 * El cliente sube el archivo con PUT a uploadUrl, usando exactamente
 * el mismo Content-Type declarado (la firma lo incluye).
 */
public record UploadUrlResponse(UUID mediaId, String uploadUrl, String contentType) {
}