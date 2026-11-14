// Manual end-to-end smoke test using only the JDK (no websocat/k6 needed):
//   java scripts/WsSmoke.java [authBase] [wsBase...]
//   java scripts/WsSmoke.java http://localhost:8080 ws://localhost:8082 ws://localhost:8083
// For each wsBase: gets a token+ticket from auth-api, checks echo + push, ticket replay -> 401, no creds -> 401.

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class WsSmoke {
    static final HttpClient http = HttpClient.newHttpClient();
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        String auth = args.length > 0 ? args[0] : "http://localhost:8080";
        String[] targets = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[] {"ws://localhost:8082"};
        for (String target : targets) {
            System.out.println("== " + target);
            String token = field(post(auth + "/auth/token", null, "{\"userId\":\"smoke-user\"}"), "token");
            String ticket = field(post(auth + "/auth/ws-ticket", token, ""), "ticket");

            var echo = connect(target + "/ws/echo?ticket=" + ticket);
            echo.ws.sendText("{\"type\":\"echo\",\"id\":\"smoke-1\",\"ts\":" + System.currentTimeMillis() + ",\"payload\":\"hi\"}", true);
            String reply = echo.next();
            check("echo reply has id/userId/srvTs", reply.contains("\"id\":\"smoke-1\"") && reply.contains("\"userId\":\"smoke-user\"") && reply.contains("srvTs"), reply);
            echo.ws.sendClose(1000, "bye").join();

            check("ticket replay rejected with 401", handshakeStatus(target + "/ws/echo?ticket=" + ticket) == 401, "");
            check("no credentials rejected with 401", handshakeStatus(target + "/ws/echo") == 401, "");

            String ticket2 = field(post(auth + "/auth/ws-ticket", token, ""), "ticket");
            var push = connect(target + "/ws/push?ticket=" + ticket2);
            push.ws.sendText("{\"type\":\"subscribe\",\"topic\":\"smoke\",\"intervalMs\":100,\"payloadBytes\":32}", true);
            int smoke = 0;
            long deadline = System.currentTimeMillis() + 3000;
            while (smoke < 3 && System.currentTimeMillis() < deadline) {
                if (push.next().contains("\"topic\":\"smoke\"")) smoke++;
            }
            check("push delivers subscribed messages", smoke >= 3, "got " + smoke);
            push.ws.sendClose(1000, "bye").join();
        }
        System.out.println(failures == 0 ? "ALL OK" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    record Conn(WebSocket ws, LinkedBlockingQueue<String> inbox) {
        String next() throws InterruptedException {
            String m = inbox.poll(5, TimeUnit.SECONDS);
            if (m == null) throw new IllegalStateException("timed out waiting for a message");
            return m;
        }
    }

    static Conn connect(String uri) {
        var inbox = new LinkedBlockingQueue<String>();
        var ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create(uri), new WebSocket.Listener() {
            final StringBuilder buf = new StringBuilder();
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                buf.append(data);
                if (last) { inbox.add(buf.toString()); buf.setLength(0); }
                w.request(1);
                return null;
            }
        }).join();
        return new Conn(ws, inbox);
    }

    static int handshakeStatus(String uri) {
        try {
            connect(uri).ws.abort();
            return 101;
        } catch (CompletionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException h) return h.getResponse().statusCode();
            throw e;
        }
    }

    static String post(String url, String bearer, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.header("authorization", "Bearer " + bearer);
        var res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException(url + " -> " + res.statusCode() + " " + res.body());
        return res.body();
    }

    static String field(String json, String name) {
        var m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("no " + name + " in " + json);
        return m.group(1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  [ok]   " : "  [FAIL] ") + what + (ok || detail.isEmpty() ? "" : " :: " + detail));
        if (!ok) failures++;
    }
}
