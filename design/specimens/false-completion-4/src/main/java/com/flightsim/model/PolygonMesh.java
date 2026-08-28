package com.flightsim.model;

import java.util.List;

public class PolygonMesh {

    private final List<float[]> vertices;
    private final List<float[]> normals;
    private final float[] color;
    private final boolean groundPlane;

    public PolygonMesh(List<float[]> vertices, List<float[]> normals, float[] color, boolean groundPlane) {
        this.vertices = vertices;
        this.normals = normals;
        this.color = color;
        this.groundPlane = groundPlane;
    }

    public int vertexCount() {
        return vertices.size();
    }

    public List<float[]> vertices() {
        return vertices;
    }

    public List<float[]> normals() {
        return normals;
    }

    public float[] color() {
        return color;
    }

    public boolean isGroundPlane() {
        return groundPlane;
    }
}