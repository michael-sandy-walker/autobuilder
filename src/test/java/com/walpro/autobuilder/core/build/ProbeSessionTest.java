package com.walpro.autobuilder.core.build;

import com.sun.net.httpserver.HttpServer;
import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EO: one sequence is one client -- cookies and a page's hidden form fields travel within it, and nowhere else. */
class ProbeSessionTest {

    private HttpServer server;
    private int port;
    private final List<String> received = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        // A page with a form: a session cookie and a hidden field the form would send.
        server.createContext("/app/login", x -> {
            x.getResponseHeaders().add("Set-Cookie", "SID=before; Path=/; HttpOnly");
            byte[] page = ("<html><form action=\"/app/enter\" method=\"post\">"
                    + "<input type=\"hidden\" name=\"_token\" value=\"t&amp;1\"/><input name=\"user\"/></form>"
                    + "<form action='other'><input type=hidden name=unrelated value=x></form></html>")
                    .getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, page.length);
            x.getResponseBody().write(page);
            x.close();
        });
        server.createContext("/app/enter", x -> {
            received.add(x.getRequestMethod() + " " + x.getRequestHeaders().getFirst("Content-Type") + " cookie="
                    + x.getRequestHeaders().getFirst("Cookie") + " body="
                    + new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            x.getResponseHeaders().add("Set-Cookie", "SID=after; Path=/");
            x.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/app/home?welcome=1");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/app/home", x -> {
            received.add("GET cookie=" + x.getRequestHeaders().getFirst("Cookie"));
            x.getResponseHeaders().add("Set-Cookie", "SID=; Max-Age=0");
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> send(ProbeSession s, String method, String path, String body) throws Exception {
        HttpRequest r = s.request(port, "/app", 2000, new ProbeRequest(method, path, body));
        HttpResponse<String> response = client.send(r, HttpResponse.BodyHandlers.ofString());
        s.answered(r, response);
        return response;
    }

    @Test
    void aFormCarriesItsPagesHiddenFieldsAndTheCookiesFollow() throws Exception {
        ProbeSession s = new ProbeSession();
        send(s, "GET", "/login", null);
        HttpResponse<String> entered = send(s, "POST", "/enter", "user=lisa m&password=7&5");
        send(s, "GET", "/home", null);
        send(s, "GET", "/home", null);

        assertThat(received.get(0)).isEqualTo("POST application/x-www-form-urlencoded cookie=SID=before "
                + "body=_token=t%261&user=lisa+m&password=7&5=");
        assertThat(ProbeSession.location(entered)).isEqualTo("/app/home?welcome=1");
        assertThat(received.get(1)).isEqualTo("GET cookie=SID=after");
        assertThat(received.get(2)).as("a cookie the answer took back is no longer sent").isEqualTo("GET cookie=null");
    }

    @Test
    void aNewSequenceIsANewClientAndWhatTheClaimStatesWins() throws Exception {
        ProbeSession first = new ProbeSession();
        send(first, "GET", "/login", null);
        send(first, "POST", "/enter", "_token=mine&user=a");
        ProbeSession second = new ProbeSession();
        send(second, "POST", "/enter", "user=b");

        assertThat(received.get(0)).contains("body=_token=mine&user=a");
        assertThat(received.get(1)).isEqualTo("POST application/x-www-form-urlencoded cookie=null body=user=b");
    }

    @Test
    void aFormSentWithoutItsPageIsGivenThePageTheProgramShowed() throws Exception {
        java.util.Map<String, String> formPages = new java.util.concurrent.ConcurrentHashMap<>();
        ProbeSession asking = new ProbeSession(formPages);
        send(asking, "GET", "/login", null);
        assertThat(asking.lastForms()).extracting(ProbeSession.Form::target).containsExactly("/app/enter", "/app/other");
        assertThat(asking.lastForms().get(0).fields()).containsExactly("user");
        assertThat(asking.lastForms().get(0).hidden()).containsExactly("_token");
        assertThat(formPages).containsEntry("/app/enter", "/app/login");

        ProbeSession claim = new ProbeSession(formPages);
        ProbeRequest post = new ProbeRequest("POST", "/enter", "user=a");
        assertThat(claim.pageToFetchFirst("/app", post)).contains("/app/login");
        send(claim, "GET", claim.pageToFetchFirst("/app", post).get(), null);
        assertThat(claim.pageToFetchFirst("/app", post)).as("just fetched, not again").isEmpty();
        send(claim, "POST", "/enter", "user=a");
        assertThat(claim.pageToFetchFirst("/app", post)).as("the page is opened again for a second sending").contains("/app/login");
        assertThat(received.get(0)).contains("cookie=SID=before").contains("body=_token=t%261&user=a");

        assertThat(new ProbeSession(formPages).pageToFetchFirst("/app", new ProbeRequest("POST", "/enter", "{\"a\":1}")))
                .as("a JSON body is not a form").isEmpty();
        assertThat(new ProbeSession().pageToFetchFirst("/app", post)).as("nothing shown, nothing fetched").isEmpty();
    }

    @Test
    void aJsonBodyIsSentAsItAlwaysWas() throws Exception {
        ProbeSession s = new ProbeSession();
        send(s, "POST", "/enter", "{\"title\":\"a=b\"}");

        assertThat(received.get(0)).isEqualTo("POST application/json cookie=null body={\"title\":\"a=b\"}");
        assertThat(ProbeSession.pageField("<form><input type='hidden' name='_token' value='t1'></form>", "_token")).contains("t1");
        assertThat(ProbeSession.pageField("{\"_token\":\"x\"}", "_token")).isEmpty();
        assertThat(ProbeSession.isForm("[1]")).isFalse();
        assertThat(ProbeSession.isForm("\"a=b\"")).isFalse();
        assertThat(ProbeSession.isForm("name=x")).isTrue();
    }
}
