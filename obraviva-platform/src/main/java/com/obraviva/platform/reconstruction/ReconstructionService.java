package com.obraviva.platform.reconstruction;

import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * Puerto del servicio externo de reconstrucción 3D por Gaussian Splatting.
 *
 * <p>La reconstrucción no es el aporte de ingeniería de la tesis: se integra un
 * servicio de terceros. Esta abstracción permite cambiar de proveedor sin tocar el
 * núcleo, y —más importante para el día a día— desarrollar contra un adaptador
 * simulado sin consumir créditos ni depender de conectividad.
 *
 * <p>Trabaja sobre archivos locales, igual que {@code VideoProcessor}: no conoce
 * MinIO ni la cola de trabajos.
 */
public interface ReconstructionService {

    /**
     * Envía un video ya preprocesado y devuelve el identificador de la tarea en el
     * servicio externo, que se guarda en {@code external_reference} del trabajo.
     *
     * <p>Esta es la operación que consume un crédito.
     *
     * @throws ReconstructionRejectedException si el servicio rechaza el envío de forma definitiva
     * @throws ReconstructionException si falla técnicamente y conviene reintentar
     */
    String submitVideo(Path videoFile);

    /**
     * Consulta el estado de una tarea. Se invoca periódicamente desde la cola.
     *
     * @throws ReconstructionRejectedException si la tarea no existe en el servicio
     * @throws ReconstructionException si falla técnicamente
     */
    ReconstructionStatus checkStatus(String externalReference);

    /**
     * Descarga el resultado a un archivo local.
     *
     * <p>El resultado es un <strong>archivo comprimido</strong>, no un PLY suelto:
     * así lo entrega KIRI. La descompresión y la localización del PLY dentro del
     * ZIP son responsabilidad de la etapa siguiente del pipeline.
     *
     * @throws ReconstructionRejectedException si el resultado venció o no existe
     * @throws ReconstructionException si falla técnicamente
     */
    void downloadResult(String externalReference, Path destination);

    /**
     * Créditos que le quedan a la cuenta, para que el dueño sepa cuántos videos puede
     * mandar. Es una consulta: no consume nada y no lanza. Vacío si el proveedor no
     * trabaja con créditos (el simulado) o si no respondió: nunca un número inventado.
     */
    OptionalInt availableCredits();
}