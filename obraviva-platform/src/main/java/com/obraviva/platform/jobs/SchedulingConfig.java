package com.obraviva.platform.jobs;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita las tareas programadas. El planificador por defecto de Spring usa un
 * único hilo, lo que hace que los workers de la cola sean secuenciales sin
 * configuración adicional — suficiente para el volumen del prototipo y necesario
 * para que el trabajo tomado no compita consigo mismo.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}