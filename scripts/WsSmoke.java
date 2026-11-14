// JDK만으로 하는 수동 E2E 스모크 테스트(websocat/k6 불필요):
//   java scripts/WsSmoke.java [authBase] [wsBase...]
//   java scripts/WsSmoke.java http://localhost:8080 ws://localhost:8082 ws://localhost:8083 ws://localhost:8085
// wsBase마다 auth-api에서 토큰과 티켓을 받아, 그 주소가 제공하는 서비스만 확인한다(핸드셰이크가 404면 건너뜀).
//   - ws-backend(/ws/echo, /ws/push): echo + push, 응답 server가 ws-backend@, 티켓 재사용 -> 401, 자격증명 없음 -> 401
//   - ws-chat(/ws/chat): 두 연결이 같은 방에 join한 뒤 say가 둘 다에게 도착, 응답 server가 ws-chat@
//   - 라우트/엔드포인트가 없는 경로(/ws/unknown) -> 404
// 게이트웨이(8082)는 두 서비스를 모두 제공하므로 경로별 라우팅까지 확인된다.

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
            boolean echoServed = handshakeStatus(target + "/ws/echo?ticket=" + ticket(auth, token)) != 404;
            boolean chatServed = handshakeStatus(target + "/ws/chat?ticket=" + ticket(auth, token)) != 404;
            check("serves at least one service", echoServed || chatServed, "");
            if (echoServed) checkEchoPush(target, auth, token);
            else System.out.println("  [skip] /ws/echo, /ws/push not served here");
            if (chatServed) checkChat(target, auth, token);
            else System.out.println("  [skip] /ws/chat not served here");
            check("unrouted ws path rejected with 404", handshakeStatus(target + "/ws/unknown?ticket=" + ticket(auth, token)) == 404, "");
        }
        System.out.println(failures == 0 ? "ALL OK" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static String ticket(String auth, String token) throws Exception {
        return field(post(auth + "/auth/ws-ticket", token, ""), "ticket");
    }

    static void checkEchoPush(String target, String auth, String token) throws Exception {
        String ticket = ticket(auth, token);
        var echo = connect(target + "/ws/echo?ticket=" + ticket);
        echo.ws.sendText("{\"type\":\"echo\",\"id\":\"smoke-1\",\"ts\":" + System.currentTimeMillis() + ",\"payload\":\"hi\"}", true);
        String reply = echo.next();
        check("echo reply has id/userId/srvTs", reply.contains("\"id\":\"smoke-1\"") && reply.contains("\"userId\":\"smoke-user\"") && reply.contains("srvTs"), reply);
        check("echo is served by ws-backend", reply.contains("\"server\":\"ws-backend@"), reply);
        echo.ws.sendClose(1000, "bye").join();

        check("ticket replay rejected with 401", handshakeStatus(target + "/ws/echo?ticket=" + ticket) == 401, "");
        check("no credentials rejected with 401", handshakeStatus(target + "/ws/echo") == 401, "");

        var push = connect(target + "/ws/push?ticket=" + ticket(auth, token));
        push.ws.sendText("{\"type\":\"subscribe\",\"topic\":\"smoke\",\"intervalMs\":100,\"payloadBytes\":32}", true);
        int smoke = 0;
        long deadline = System.currentTimeMillis() + 3000;
        while (smoke < 3 && System.currentTimeMillis() < deadline) {
            if (push.next().contains("\"topic\":\"smoke\"")) smoke++;
        }
        check("push delivers subscribed messages", smoke >= 3, "got " + smoke);
        push.ws.sendClose(1000, "bye").join();
    }

    static void checkChat(String target, String auth, String token) throws Exception {
        var a = connect(target + "/ws/chat?ticket=" + ticket(auth, token));
        var b = connect(target + "/ws/chat?ticket=" + ticket(auth, token));
        for (var c : new Conn[] {a, b}) {
            c.ws.sendText("{\"type\":\"join\",\"room\":\"smoke\"}", true);
            String joined = c.next();
            check("chat join acknowledged", joined.contains("\"type\":\"joined\""), joined);
        }
        a.ws.sendText("{\"type\":\"say\",\"room\":\"smoke\",\"text\":\"hello-room\"}", true);
        for (var c : new Conn[] {a, b}) {
            String msg = c.next();
            check("chat message delivered to room member", msg.contains("\"type\":\"message\"") && msg.contains("\"text\":\"hello-room\""), msg);
            check("chat is served by ws-chat", msg.contains("\"server\":\"ws-chat@"), msg);
        }
        check("chat without credentials rejected with 401", handshakeStatus(target + "/ws/chat") == 401, "");
        a.ws.sendClose(1000, "bye").join();
        b.ws.sendClose(1000, "bye").join();
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
