package com.obraviva.platform.sharing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generación y hash de los tokens de enlace público.
 *
 * <p>El token se genera con {@link SecureRandom} y 32 bytes de entropía. No se usa
 * UUID: aunque un UUID aleatorio tiene entropía suficiente, su formato es reconocible
 * e invita a probar variantes, y no todos los generadores de UUID usan una fuente
 * criptográfica. Acá la fuente es explícita.
 *
 * <p>Se codifica en base64 seguro para URL y sin relleno, porque el token va dentro
 * de una dirección web que la gente copia y pega en mensajes.
 *
 * <p>El hash es SHA-256 sin sal, y eso es deliberado: a diferencia de una contraseña,
 * el token tiene 256 bits de aleatoriedad, así que no hay diccionario ni tabla
 * precalculada que lo alcance. Una función lenta como bcrypt sólo agregaría latencia
 * a cada visita de la vista pública sin aportar seguridad.
 */
public final class ShareToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private ShareToken() {
    }

    /** Devuelve un token nuevo en claro. Existe una sola vez: después sólo queda su hash. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hash hexadecimal en minúsculas, de 64 caracteres: el largo de la columna. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en toda plataforma Java: si falta, el entorno
            // está roto de un modo que no tiene sentido manejar.
            throw new IllegalStateException("SHA-256 no disponible en esta plataforma", e);
        }
    }
}