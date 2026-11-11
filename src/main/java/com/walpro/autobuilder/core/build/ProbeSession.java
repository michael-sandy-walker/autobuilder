package com.walpro.autobuilder.core.build;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EO: what one sequence of requests carries from one request to the next, as one client would -- the cookies its
 * answers set, and the hidden fields of the forms on the pages it fetched.
 *
 * <p>One per sequence, never shared: a claim starts as a new client, so what one claim logged in as says nothing about
 * the next. A redirect is not followed; where it points is kept ({@link #location}) and its status is the answer.
 *
 * <p>Nothing here knows how any project authenticates. A cookie is sent back because the answer set it; a hidden field
 * travels because the page's own form would have sent it. A project without either sees the requests it always saw.
 */
public final class ProbeSession {

    private static final Pattern FORM = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form\\s*>");
    private static final Pattern INPUT = Pattern.compile("(?is)<input\\b([^>]*)>");
    private static final Pattern ATTRIBUTE =
            Pattern.compile("(?is)([a-z][a-z0-9_:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

    /** One form of a page: where it posts to, the fields it carries on its own, and the ones a user fills in. */
    public record Form(String target, List<String> hidden, List<String> fields) {
    }

    private static final Pattern FIELD = Pattern.compile("(?is)<(?:input|select|textarea)\\b([^>]*)>");

    private final Map<String, String> cookies = new LinkedHashMap<>();
    /**
     * Shared by the sequences of one started program, or null: the path a form posts to -> the page seen carrying it.
     * A form is sent from its page; a sequence that sends one without having fetched the page fetches it first
     * ({@link #pageToFetchFirst}), as the person at a browser must have.
     */
    private final Map<String, String> formPages;
    private List<Form> lastForms = List.of();

    ProbeSession() {
        this(null);
    }

    ProbeSession(Map<String, String> formPages) {
        this.formPages = formPages;
    }

    /** The forms of the page answered last; empty when the last answer carried none. */
    List<Form> lastForms() {
        return lastForms;
    }

    /**
     * The page to fetch before {@code r}: {@code r} sends a form, the answer this client got last is not a page whose
     * form posts there, and a page carrying that form has been seen on this program. Empty otherwise. A page held from
     * further back is not sent from: what it carried may no longer be accepted (field run 4: a form's hidden field from
     * before a login was refused after it), and someone at a browser would open the page again.
     */
    Optional<String> pageToFetchFirst(String prefix, CriterionProbeSynthesizer.ProbeRequest r) {
        if (formPages == null || r.body() == null || r.body().isBlank() || !isForm(r.body())) return Optional.empty();
        String target = withoutQuery(BootGate.served(r.path(), prefix));
        if (lastForms.stream().anyMatch(f -> f.target().equals(target))) return Optional.empty();
        return Optional.ofNullable(formPages.get(target));
    }
    /** The path a form posts to (as served, without a query) -> its hidden fields; the page fetched last wins. */
    private final Map<String, Map<String, String>> hiddenByTarget = new LinkedHashMap<>();

    /** The request as this client sends it now: its cookies, and a form body with its page's hidden fields. */
    HttpRequest request(int port, String prefix, long requestTimeoutMs, CriterionProbeSynthesizer.ProbeRequest r) {
        String path = BootGate.served(r.path(), prefix);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofMillis(requestTimeoutMs));
        if (!cookies.isEmpty()) {
            StringBuilder c = new StringBuilder();
            cookies.forEach((n, v) -> c.append(c.length() == 0 ? "" : "; ").append(n).append('=').append(v));
            builder.header("Cookie", c.toString());
        }
        String body = r.body();
        if (body == null || body.isBlank()) {
            Map<String, String> hidden = hiddenByTarget.get(withoutQuery(path));
            if (hidden == null || "GET".equals(r.method()) || "DELETE".equals(r.method())) {
                builder.method(r.method(), HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                        .method(r.method(), HttpRequest.BodyPublishers.ofString(encode(hidden), StandardCharsets.UTF_8));
            }
        } else if (isForm(body)) {
            Map<String, String> fields = new LinkedHashMap<>();
            Map<String, String> hidden = hiddenByTarget.get(withoutQuery(path));
            if (hidden != null) fields.putAll(hidden);
            fields.putAll(formFields(body));   // what the claim states wins over what the page carried
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .method(r.method(), HttpRequest.BodyPublishers.ofString(encode(fields), StandardCharsets.UTF_8));
        } else {
            builder.header("Content-Type", "application/json")
                    .method(r.method(), HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    /** Keeps what the answer gave a client to carry: cookies set or taken back, and the forms of a page. */
    void answered(HttpRequest sent, HttpResponse<String> response) {
        for (String header : response.headers().allValues("set-cookie")) {
            String pair = header.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).strip();
            String value = pair.substring(eq + 1).strip();
            boolean takenBack = value.isEmpty() || header.toLowerCase(Locale.ROOT).matches(".*;\\s*max-age\\s*=\\s*0\\s*(;.*)?");
            if (takenBack) cookies.remove(name);
            else cookies.put(name, value);
        }
        lastForms = List.of();
        String body = response.body();
        if (body == null || body.indexOf('<') < 0) return;
        List<Form> forms = new ArrayList<>();
        Matcher form = FORM.matcher(body);
        while (form.find()) {
            Map<String, String> attributes = attributes(form.group(1));
            Map<String, String> hidden = new LinkedHashMap<>();
            List<String> fields = new ArrayList<>();
            Matcher input = FIELD.matcher(form.group(2));
            while (input.find()) {
                Map<String, String> a = attributes(input.group(1));
                String name = a.get("name");
                if (name == null || name.isBlank()) continue;
                if ("hidden".equalsIgnoreCase(a.get("type"))) hidden.put(name, a.getOrDefault("value", ""));
                else if (!"submit".equalsIgnoreCase(a.get("type")) && !fields.contains(name)) fields.add(name);
            }
            Optional<String> target = target(sent.uri(), attributes.get("action"));
            if (target.isEmpty()) continue;
            hiddenByTarget.put(target.get(), hidden);
            forms.add(new Form(target.get(), List.copyOf(hidden.keySet()), List.copyOf(fields)));
            if (formPages != null && response.statusCode() < 300) {
                String page = sent.uri().getRawPath() + (sent.uri().getRawQuery() == null ? "" : "?" + sent.uri().getRawQuery());
                formPages.putIfAbsent(target.get(), page);
            }
        }
        lastForms = List.copyOf(forms);
    }

    /** The value a page's form field named {@code name} carries; empty when the body is no page or has no such field. */
    static Optional<String> pageField(String body, String name) {
        if (body == null || body.indexOf('<') < 0) return Optional.empty();
        Matcher input = INPUT.matcher(body);
        while (input.find()) {
            Map<String, String> a = attributes(input.group(1));
            if (name.equals(a.get("name")) && a.get("value") != null) return Optional.of(a.get("value"));
        }
        return Optional.empty();
    }

    /** Where a redirect points, as the answer wrote it; null when the answer is not a redirect. */
    static String location(HttpResponse<?> response) {
        int s = response.statusCode();
        if (s < 300 || s >= 400) return null;
        return response.headers().firstValue("location").map(l -> {
            // The address of this machine says nothing; the path is what a reader compares.
            try {
                URI u = URI.create(l);
                if (u.getHost() == null) return l;
                return u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            } catch (IllegalArgumentException e) {
                return l;
            }
        }).orElse(null);
    }

    /**
     * Whether a body is a form's fields ({@code name=value&name=value}) rather than a JSON text: it does not open as
     * one, and it starts with a name and an equals sign.
     */
    static boolean isForm(String body) {
        String b = body.strip();
        if (b.isEmpty()) return false;
        char first = b.charAt(0);
        if (first == '{' || first == '[' || first == '"') return false;
        return b.matches("(?s)[A-Za-z_][A-Za-z0-9_.\\[\\]-]*=.*");
    }

    /** The fields in the order written; a value is taken as written (it is encoded when sent). */
    static Map<String, String> formFields(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.strip().split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(pair.strip(), "");
            else out.put(pair.substring(0, eq).strip(), pair.substring(eq + 1));
        }
        return out;
    }

    private static String encode(Map<String, String> fields) {
        List<String> pairs = new ArrayList<>();
        fields.forEach((n, v) -> pairs.add(URLEncoder.encode(n, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return String.join("&", pairs);
    }

    /** The path a form on {@code page} posts to: its action resolved as a browser would, the page itself without one. */
    private static Optional<String> target(URI page, String action) {
        try {
            URI resolved = action == null || action.isBlank() ? page : page.resolve(unescape(action).strip());
            return Optional.ofNullable(resolved.getRawPath()).filter(p -> !p.isEmpty());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String withoutQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ATTRIBUTE.matcher(tag);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            out.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), unescape(value));
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
