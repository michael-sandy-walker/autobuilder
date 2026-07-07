package com.walpro.autobuilder.core.build;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Launched as a real child process by {@link BootGateTest} to stand in for a packaged
 * application. Serves a fixed endpoint surface: {@code /items} answers 200, {@code /missing}
 * 404, {@code /failing} 500, everything else (including the readiness probe on {@code /}) 404.
 * The port comes from the {@code BOOT_GATE_TEST_PORT} environment variable when set (exercising
 * the gate's port-env-var handoff), else from {@code args[0]} (exercising the {@code {port}}
 * command-template substitution). Stays up until killed by the gate.
 */
public final class BootGateProbeServer {

    private BootGateProbeServer() {
    }

    public static void main(String[] args) throws Exception {
        String env = System.getenv("BOOT_GATE_TEST_PORT");
        int port = Integer.parseInt(env != null && !env.isBlank() ? env : args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> respond(ex, 404));
        server.createContext("/items", ex -> respond(ex, 200));
        server.createContext("/missing", ex -> respond(ex, 404));
        server.createContext("/failing", ex -> respond(ex, 500));
        server.start();
        Thread.sleep(120_000); // the gate destroys the process long before this elapses
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
