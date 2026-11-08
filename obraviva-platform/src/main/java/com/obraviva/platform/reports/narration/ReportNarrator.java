package com.obraviva.platform.reports.narration;

import com.obraviva.platform.reports.ReportData;

/**
 * Puerto de redacción del reporte de período.
 *
 * <p>Recibe el snapshot ya compuesto y devuelve prosa. No consulta la base, no
 * calcula y no decide qué datos entran: todo eso lo resolvió el motor de datos antes.
 * La superficie mínima es deliberada — es lo que permite tener un adaptador simulado
 * para el desarrollo diario y cambiar de proveedor sin tocar el resto del módulo.
 */
public interface ReportNarrator {

    /**
     * @throws NarrationException si el proveedor falla o su respuesta no sirve
     */
    Narration narrate(ReportData data);
}