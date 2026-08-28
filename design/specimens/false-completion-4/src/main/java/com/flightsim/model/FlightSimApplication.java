package com.flightsim.model;

import com.flightsim.model.input.FlightControls;
import com.flightsim.model.input.KeyListener;
import com.flightsim.model.render.OpenGL;
import com.flightsim.model.scene.EnvironmentGenerator;
import com.flightsim.model.scene.SceneGraph;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Scanner;

public class FlightSimApplication {

    private SceneGraph scene;
    private FlightControls controls;
    private OpenGL renderer;
    private boolean paused;
    private boolean running;

    public static void main(String[] args) {
        new FlightSimApplication().showStartMenu();
    }

    private void showStartMenu() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("=== Flight Simulator ===");
            System.out.println("1. Start");
            System.out.println("2. Pause");
            System.out.println("3. Exit");
            System.out.print("Select option: ");
            String input = scanner.nextLine().trim();
            if ("1".equals(input)) {
                startSimulation();
                break;
            } else if ("2".equals(input)) {
                togglePause();
            } else if ("3".equals(input)) {
                System.out.println("Exiting...");
                System.exit(0);
            } else {
                System.out.println("Invalid option. Please try again.");
            }
        }
    }

    private void startSimulation() {
        scene = new EnvironmentGenerator().generate(20);
        controls = new FlightControls();
        renderer = new OpenGL();
        paused = false;
        running = true;

        JFrame frame = new JFrame("Flight Simulator");
        frame.setSize(800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        KeyListener keyListener = new KeyListener(controls);
        frame.addKeyListener(keyListener);

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (running && !paused) {
                    updateAndRender();
                }
            }
        };
        frame.add(panel);
        frame.setVisible(true);

        new Timer(16, e -> {
            if (running && !paused) {
                updateAndRender();
                panel.repaint();
            }
        }).start();
    }

    private void togglePause() {
        paused = !paused;
        System.out.println(paused ? "Paused" : "Resumed");
    }

    private void updateAndRender() {
        float[] cameraPos = {0f, 10f, 0f};
        float[] cameraRot = {0f, 0f, 0f};
        controls.update(cameraPos, cameraRot, 0.016f);
        renderer.render(scene, cameraPos, cameraRot);
    }
}