package com.flightsim.model.input;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class KeyListener extends KeyAdapter {

    private final FlightControls controls;

    public KeyListener(FlightControls controls) {
        this.controls = controls;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        controls.setKey(e.getKeyCode(), true);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        controls.setKey(e.getKeyCode(), false);
    }
}