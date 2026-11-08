package com.obraviva.platform.storage;

/**
 * Falla de la infraestructura de almacenamiento. Es técnica, no del usuario:
 * un trabajo que la reciba es candidato a reintento.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}