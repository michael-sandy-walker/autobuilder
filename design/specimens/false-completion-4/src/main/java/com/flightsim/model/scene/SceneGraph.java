package com.flightsim.model.scene;

import com.flightsim.model.PolygonMesh;

import java.util.ArrayList;
import java.util.List;

public class SceneGraph {

    private final List<PolygonMesh> meshes = new ArrayList<>();
    private PolygonMesh groundPlane;

    public void addMesh(PolygonMesh mesh) {
        if (mesh.isGroundPlane()) {
            groundPlane = mesh;
        }
        meshes.add(mesh);
    }

    public List<PolygonMesh> meshes() {
        return meshes;
    }

    public PolygonMesh groundPlane() {
        return groundPlane;
    }
}