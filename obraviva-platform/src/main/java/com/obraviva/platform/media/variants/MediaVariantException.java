package com.obraviva.platform.media.variants;

/** No se pudieron generar las versiones livianas de un medio. La foto se sigue viendo con el original. */
public class MediaVariantException extends RuntimeException {

    public MediaVariantException(String message) {
        super(message);
    }
}
