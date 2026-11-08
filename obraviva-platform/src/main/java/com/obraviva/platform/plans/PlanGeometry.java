package com.obraviva.platform.plans;

/** Reglas geométricas de los planos, sin dependencias, para poder probarlas solas. */
final class PlanGeometry {

    /** Tolerancia de un centímetro: un vértice en el borde exacto no se rechaza por redondeo. */
    private static final double TOLERANCE_M = 0.01;

    private PlanGeometry() {
    }

    static boolean fitsWithin(double[] polygon, double widthM, double depthM) {
        for (int i = 0; i < polygon.length; i += 2) {
            double x = polygon[i];
            double y = polygon[i + 1];
            if (x < -TOLERANCE_M || y < -TOLERANCE_M || x > widthM + TOLERANCE_M || y > depthM + TOLERANCE_M) {
                return false;
            }
        }
        return true;
    }

    static boolean allFinite(double[] polygon) {
        for (double value : polygon) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
