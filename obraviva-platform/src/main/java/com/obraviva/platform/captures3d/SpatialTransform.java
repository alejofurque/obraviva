package com.obraviva.platform.captures3d;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Transformación espacial de una captura: posición en metros, rotación
 * Euler en grados (XYZ) y escala uniforme. La escala es un escalar porque
 * el renderer no soporta escala no uniforme en escenas de splats.
 */
@Embeddable
public class SpatialTransform {

    @Column(name = "transform_position_x", nullable = false)
    private double positionX;

    @Column(name = "transform_position_y", nullable = false)
    private double positionY;

    @Column(name = "transform_position_z", nullable = false)
    private double positionZ;

    @Column(name = "transform_rotation_x", nullable = false)
    private double rotationX;

    @Column(name = "transform_rotation_y", nullable = false)
    private double rotationY;

    @Column(name = "transform_rotation_z", nullable = false)
    private double rotationZ;

    @Column(name = "transform_scale", nullable = false)
    private double scale;

    protected SpatialTransform() {
    }

    public SpatialTransform(
            double positionX, double positionY, double positionZ,
            double rotationX, double rotationY, double rotationZ,
            double scale) {
        this.positionX = positionX;
        this.positionY = positionY;
        this.positionZ = positionZ;
        this.rotationX = rotationX;
        this.rotationY = rotationY;
        this.rotationZ = rotationZ;
        this.scale = scale;
    }

    public static SpatialTransform identity() {
        return new SpatialTransform(0, 0, 0, 0, 0, 0, 1);
    }

    public double getPositionX() {
        return positionX;
    }

    public double getPositionY() {
        return positionY;
    }

    public double getPositionZ() {
        return positionZ;
    }

    public double getRotationX() {
        return rotationX;
    }

    public double getRotationY() {
        return rotationY;
    }

    public double getRotationZ() {
        return rotationZ;
    }

    public double getScale() {
        return scale;
    }
}