package com.walpro.autobuilder.home;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * T4-ED: the home context's calls to the run's own endpoints. Relayed rather than re-implemented, so what a goal
 * is, when an input is refused and how a conflict is decided stay where the run keeps them.
 */
class RunClient {

    /** A reply as the run gave it; status 409 when no run is serving, 502 when it did not answer. */
    record Reply(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final ProjectLauncher launcher;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    RunClient(ProjectLauncher launcher) {
        this.launcher = launcher;
    }

    Reply get(String path) {
        return send(HttpRequest.newBuilder().GET(), path, Duration.ofMinutes(2));
    }

    Reply postJson(String path, String json, Duration timeout) {
        return send(HttpRequest.newBuilder().header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)), path, timeout);
    }

    Reply postText(String path, String text) {
        return postText(path, text, Duration.ofMinutes(2));
    }

    Reply postText(String path, String text, Duration timeout) {
        return send(HttpRequest.newBuilder().header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8)), path, timeout);
    }

    private Reply send(HttpRequest.Builder request, String path, Duration timeout) {
        Object port = launcher.describe().get("runPort");
        if (port == null) return new Reply(409, "{\"ok\":false,\"message\":\"no run is serving\"}");
        try {
            HttpResponse<String> res = http.send(request.uri(URI.create("http://localhost:" + port + path))
                    .timeout(timeout).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(res.statusCode(), res.body());
        } catch (IOException e) {
            return new Reply(502, "{\"ok\":false,\"message\":\"the run did not answer: " + e.getClass().getSimpleName() + "\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(502, "{\"ok\":false,\"message\":\"interrupted\"}");
        }
    }
}
