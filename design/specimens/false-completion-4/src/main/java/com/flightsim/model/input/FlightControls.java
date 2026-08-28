package com.flightsim.model.input;

import java.util.HashMap;
import java.util.Map;

public class FlightControls {

    private final Map<Integer, Boolean> keyStates = new HashMap<>();

    public void setKey(int keyCode, boolean pressed) {
        keyStates.put(keyCode, pressed);
    }

    public void update(float[] cameraPos, float[] cameraRot, float dt) {
        float speed = 50f * dt;
        float rotationSpeed = 2f * dt;

        if (isPressed(38)) { // W
            cameraPos[1] += speed;
        }
        if (isPressed(40)) { // S
            cameraPos[1] -= speed;
        }
        if (isPressed(37)) { // A
            cameraPos[0] -= speed;
        }
        if (isPressed(39)) { // D
            cameraPos[0] += speed;
        }
        if (isPressed(87)) { // Up
            cameraRot[1] += rotationSpeed;
        }
        if (isPressed(81)) { // Down
            cameraRot[1] -= rotationSpeed;
        }
        if (isPressed(65)) { // Left
            cameraRot[0] += rotationSpeed;
        }
        if (isPressed(68)) { // Right
            cameraRot[0] -= rotationSpeed;
        }
    }

    private boolean isPressed(int keyCode) {
        return keyStates.getOrDefault(keyCode, false);
    }
}