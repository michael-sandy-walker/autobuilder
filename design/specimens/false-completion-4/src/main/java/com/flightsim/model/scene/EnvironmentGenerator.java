package com.flightsim.model.scene;

import com.flightsim.model.PolygonMesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EnvironmentGenerator {

    private final Random random = new Random();

    public SceneGraph generate(int objectCount) {
        SceneGraph scene = new SceneGraph();
        scene.addMesh(createGroundPlane());
        for (int i = 0; i < objectCount; i++) {
            scene.addMesh(createRandomObject());
        }
        return scene;
    }

    private PolygonMesh createGroundPlane() {
        List<float[]> vertices = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        vertices.add(new float[]{-100f, 0f, -100f});
        vertices.add(new float[]{100f, 0f, -100f});
        vertices.add(new float[]{100f, 0f, 100f});
        vertices.add(new float[]{-100f, 0f, 100f});
        for (int i = 0; i < 4; i++) {
            normals.add(new float[]{0f, 1f, 0f});
        }
        return new PolygonMesh(vertices, normals, new float[]{0.3f, 0.6f, 0.3f}, true);
    }

    private PolygonMesh createRandomObject() {
        List<float[]> vertices = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        float x = random.nextFloat() * 200f - 100f;
        float z = random.nextFloat() * 200f - 100f;
        float height = random.nextFloat() * 20f + 5f;
        vertices.add(new float[]{x, 0f, z});
        vertices.add(new float[]{x + 5f, 0f, z});
        vertices.add(new float[]{x + 5f, height, z});
        vertices.add(new float[]{x, height, z});
        for (int i = 0; i < 4; i++) {
            normals.add(new float[]{0f, 0f, 1f});
        }
        float r = random.nextFloat();
        float g = random.nextFloat();
        float b = random.nextFloat();
        return new PolygonMesh(vertices, normals, new float[]{r, g, b}, false);
    }
}