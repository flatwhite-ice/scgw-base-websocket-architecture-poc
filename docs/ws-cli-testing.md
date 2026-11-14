# CLI로 WebSocket 테스트하기 (websocat)

게이트웨이(relay)와 direct 경로에 WebSocket을 연결해 수동으로 확인하는 명령어 모음이다.
WebSocket 서비스는 두 개다: **ws-backend**(echo/push)와 **ws-chat**(방 단위 채팅). 게이트웨이가 경로로 서비스를 고른다.
아래 명령은 websocat 1.14.1 / macOS 기준으로 실제 실행해 결과를 확인했다(2026-11-15).

## 1. 준비

### 설치

```bash
brew install websocat     # 일반 formula라 sudo 불필요
websocat --version        # websocat 1.14.1
```

`curl`과 `jq`는 macOS에 기본으로 들어 있다(`/usr/bin`).

### 앱 기동

| 앱 | 포트 | 실행 |
|---|---|---|
| auth-api | 8070 | `./gradlew :modules:auth-api:bootRun` |
| ws-gateway | 8080 | `./gradlew :modules:ws-gateway:bootRun` |
| ws-backend (relay, 기본값) | 8090 | `./gradlew :modules:ws-backend:bootRun` |
| ws-chat (relay, 기본값) | 8091 | `./gradlew :modules:ws-chat:bootRun` |
| ws-backend (direct, 선택) | 8092 | `./gradlew :modules:ws-backend:bootRun --args='--spring.profiles.active=direct'` |
| ws-chat (direct, 선택) | 8093 | `./gradlew :modules:ws-chat:bootRun --args='--spring.profiles.active=direct'` |

- 경로 구성
  - relay: 클라이언트 → **8080(gateway)** → 경로에 따라
    - `/ws/echo`, `/ws/push` → 8090(ws-backend), 인증 필요
    - `/ws/chat`, `/ws/chat/...` → 8091(ws-chat), 인증 필요
    - `/ws/public/...` → 8090(ws-backend), **인증 없음**(공개 push)
    - 그 외 `/ws/...` → 게이트웨이가 404
  - direct: 클라이언트 → **8092(ws-backend)** 또는 **8093(ws-chat)**
- relay 인스턴스(8090, 8091)는 게이트웨이가 넣어 준 헤더를 신뢰한다. 따라서 클라이언트는 항상 8080으로 접속한다.

## 2. 토큰과 티켓 발급

```bash
# access token: 유효 15분, 여러 번 사용 가능
TOKEN=$(curl -s -X POST localhost:8070/auth/token \
  -H 'content-type: application/json' -d '{"userId":"u1"}' | jq -r .token)

# WS 티켓: 유효 30초, 1회용. 연결할 때마다 새로 발급한다
TICKET=$(curl -s -X POST localhost:8070/auth/ws-ticket \
  -H "authorization: Bearer $TOKEN" | jq -r .ticket)
```

인증 방법은 두 가지다.

| 방식 | 사용법 | 특징 |
|---|---|---|
| Bearer 헤더 | `-H "Authorization: Bearer $TOKEN"` | 반복 테스트에 편하다 |
| 티켓 쿼리 | `?ticket=$TICKET` | 브라우저와 같은 방식이다. 브라우저는 WS 요청에 헤더를 못 넣는다 |

## 3. 대화형 연결

```bash
websocat ws://localhost:8080/ws/echo -H "Authorization: Bearer $TOKEN"
# 또는
websocat "ws://localhost:8080/ws/echo?ticket=$TICKET"
```

연결된 뒤 한 줄씩 입력하면 서버 응답이 바로 출력된다. 종료는 `Ctrl+C`다.

```json
{"type":"echo","id":"1","ts":0,"payload":"hello"}
{"type":"subscribe","topic":"ticker","intervalMs":2000,"payloadBytes":16}
{"type":"unsubscribe"}
```

| 메시지 | 서버 동작 |
|---|---|
| `echo` | 보낸 JSON에 `srvTs`(서버 수신 시각), `userId`, `server`를 붙여 그대로 돌려준다 |
| `subscribe` | `intervalMs` 주기로 `payloadBytes` 크기의 `push` 메시지를 보낸다 |
| `unsubscribe` | push를 멈춘다 |
| 잘못된 JSON 또는 모르는 type | `{"type":"error","reason":...}`로 응답하고 연결은 유지한다 |

`/ws/push`로 연결하면 접속 즉시 기본 구독이 시작된다(`ticker`, 1초, 256B).

### 응답의 `server` 필드: 어느 backend가 처리했나

모든 서버 메시지(echo, push, error)에는 그 메시지를 보낸 **backend 인스턴스**가 `server` 필드로 들어 있다.

- 형식은 `<앱 이름>@<호스트>:<실제 포트>`이다. 예: `ws-backend@flatwhite-mbp.local:8090`
  - K8s에서는 `HOSTNAME`(파드 이름)이 호스트 자리에 들어간다.
  - `ws.instance-id` 설정으로 직접 지정할 수도 있다.
- gateway를 거쳐도 backend 값이 찍힌다. gateway는 프레임을 수정하지 않고 그대로 중계한다.
- backend를 여러 대 두면 gateway의 로드밸런싱 결과를 이 값으로 확인할 수 있다. 아래는 backend 2대(18081, 18091) 뒤에서 확인한 실제 출력이다.

```text
{"type":"echo","id":"1",...,"userId":"u1","server":"ws-backend@flatwhite-mbp.local:18081"}
{"type":"echo","id":"2",...,"userId":"u1","server":"ws-backend@flatwhite-mbp.local:18091"}
{"type":"echo","id":"3",...,"userId":"u1","server":"ws-backend@flatwhite-mbp.local:18081"}
{"type":"push","topic":"ticker","seq":0,"srvTs":...,"server":"ws-backend@flatwhite-mbp.local:18081","payload":"xxxxxxxxxxxxxxxx"}
{"type":"error","reason":"invalid json","server":"ws-backend@flatwhite-mbp.local:18091"}
```

연결이 처리되는 backend는 연결 시점에 정해진다. 같은 연결에서 오는 메시지는 모두 같은 `server` 값을 가진다.

> **주의:** websocat 1.x의 `-H`는 값을 여러 개 받는 옵션이라, `-H` 뒤에 URL을 쓰면 URL까지 헤더로 먹는다.
> 그 결과 `No URL specified` 오류가 난다. **URL을 먼저 쓰고 `-H`를 뒤에** 두거나, `--header="Authorization: Bearer $TOKEN"` 형식을 쓴다.

## 4. 한 줄 확인 명령 (스크립트용)

`-n -1`은 메시지 하나를 보내고 응답 하나를 받은 뒤 종료하는 옵션이다.

### 4.1 정상 동작

```bash
# echo (Bearer)
echo '{"type":"echo","id":"1","ts":0,"payload":"hello"}' \
  | websocat -n -1 ws://localhost:8080/ws/echo -H "Authorization: Bearer $TOKEN"
# → {"type":"echo","id":"1","ts":0,"payload":"hello","srvTs":...,"userId":"u1","server":"ws-backend@<host>:8090"}

# echo (티켓)
TICKET=$(curl -s -X POST localhost:8070/auth/ws-ticket -H "authorization: Bearer $TOKEN" | jq -r .ticket)
echo '{"type":"echo","id":"2","payload":"via-ticket"}' \
  | websocat -n -1 "ws://localhost:8080/ws/echo?ticket=$TICKET"
# → {"type":"echo","id":"2","payload":"via-ticket","srvTs":...,"userId":"u1","server":"ws-backend@<host>:8090"}

# push 수신: stdin을 열어 둔 채 몇 개만 받기
sleep 3 | websocat ws://localhost:8080/ws/push -H "Authorization: Bearer $TOKEN" | head -2
# → {"type":"push","topic":"ticker","seq":0,...,"server":"ws-backend@<host>:8090",...}
#   {"type":"push","topic":"ticker","seq":1,...}

# subscribe 파라미터 변경 후 스트림 받기
{ echo '{"type":"subscribe","topic":"fast","intervalMs":200,"payloadBytes":8}'; sleep 1; } \
  | websocat ws://localhost:8080/ws/echo -H "Authorization: Bearer $TOKEN" | head -3
# → {"type":"push","topic":"fast","seq":0,...,"payload":"xxxxxxxx"} ... (seq 0,1,2)
```

`head`가 먼저 끝나면서 나오는 `Broken pipe` 메시지는 무시해도 된다.

### 4.2 인증과 보안 검증

```bash
# 티켓 재사용 → 401 (위에서 이미 쓴 $TICKET으로 다시 연결)
echo '{"type":"echo","id":"3"}' | websocat -n -1 "ws://localhost:8080/ws/echo?ticket=$TICKET"
# → websocat: WebSocketError: ... Received unexpected status code (401 Unauthorized)

# 자격증명 없음 → 401
websocat -1 ws://localhost:8080/ws/echo </dev/null
# → ... (401 Unauthorized)

# 헤더 스푸핑은 무시된다: X-User-Id: evil을 보내도 토큰의 사용자(u1)로 처리된다
TICKET=$(curl -s -X POST localhost:8070/auth/ws-ticket -H "authorization: Bearer $TOKEN" | jq -r .ticket)
echo '{"type":"echo","id":"spoof"}' \
  | websocat -n -1 "ws://localhost:8080/ws/echo?ticket=$TICKET" -H "X-User-Id: evil"
# → {"type":"echo","id":"spoof",...,"userId":"u1","server":"ws-backend@<host>:8090"}
```

### 4.3 curl로 업그레이드 전 401 확인

websocat 없이 상태 코드만 본다. 인증 실패는 업그레이드(101) 전에 401로 거절된다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  localhost:8080/ws/echo
# → 401
```

## 5. 경로별 라우팅: ws-chat

같은 게이트웨이(8080)에서 경로만 `/ws/chat`으로 바꾸면 ws-chat으로 연결된다. 응답의 `server` 필드로 어느 서비스가 처리했는지 보인다.

### 프로토콜

| 보내는 메시지 | 받는 메시지 |
|---|---|
| `{"type":"join","room":"lobby"}` | `{"type":"joined","room":"lobby","userId":...,"members":<방 인원>,"server":...}` |
| `{"type":"say","room":"lobby","text":"hi"}` | 방의 모든 멤버(보낸 사람 포함)에게 `{"type":"message","room":"lobby","from":...,"text":"hi","srvTs":...,"server":...}` |
| `{"type":"leave","room":"lobby"}` | `{"type":"left","room":"lobby","server":...}` |

- join하지 않은 방에 say하면 `{"type":"error","reason":"not joined"}`, room이 없으면 `"room required"`다. 오류 형식은 ws-backend와 같다(`invalid json`, `unknown type`).
- 연결이 끊기면 들어가 있던 방에서 자동으로 나간다.
- 방은 ws-chat **인스턴스 안에서만** 공유된다. ws-chat을 여러 대 띄우면 같은 방 이름이라도 인스턴스마다 따로다(decisions D18).

### 두 사용자 채팅

터미널 두 개를 쓴다. 각자 토큰을 받고, 연결할 때마다 새 티켓을 쓴다.

```bash
# 터미널 1 (u2): 방에 들어가서 기다린다
T2=$(curl -s -X POST localhost:8070/auth/token -H 'content-type: application/json' -d '{"userId":"u2"}' | jq -r .token)
websocat "ws://localhost:8080/ws/chat?ticket=$(curl -s -X POST localhost:8070/auth/ws-ticket -H "Authorization: Bearer $T2" | jq -r .ticket)"
{"type":"join","room":"lobby"}
# → {"type":"joined","room":"lobby","userId":"u2","members":1,"server":"ws-chat@<host>:8091"}

# 터미널 2 (u1): 같은 방에 들어가서 말한다
websocat "ws://localhost:8080/ws/chat?ticket=$(curl -s -X POST localhost:8070/auth/ws-ticket -H "Authorization: Bearer $TOKEN" | jq -r .ticket)"
{"type":"join","room":"lobby"}
# → {"type":"joined","room":"lobby","userId":"u1","members":2,"server":"ws-chat@<host>:8091"}
{"type":"say","room":"lobby","text":"hi"}
# → {"type":"message","room":"lobby","from":"u1","text":"hi","srvTs":...,"server":"ws-chat@<host>:8091"}
#   터미널 1에도 같은 message가 도착한다
```

### 라우팅 확인

```bash
# 같은 게이트웨이, 경로만 다르다: server 필드가 서비스마다 다르다
echo '{"type":"echo","id":"1"}' | websocat -n1 "ws://localhost:8080/ws/echo?ticket=$(curl -s -X POST localhost:8070/auth/ws-ticket -H "Authorization: Bearer $TOKEN" | jq -r .ticket)"
# → {"type":"echo","id":"1",...,"server":"ws-backend@<host>:8090"}

# 라우트가 없는 경로 → 게이트웨이가 404 (인증 필터도 실행되지 않는다)
websocat -n1 "ws://localhost:8080/ws/unknown?ticket=$(curl -s -X POST localhost:8070/auth/ws-ticket -H "Authorization: Bearer $TOKEN" | jq -r .ticket)"
# → websocat: WebSocketError: ... Received unexpected status code (404 Not Found)

# curl로 보면: 라우트가 있는 경로는 미인증 401, 없는 경로는 404
for p in /ws/echo /ws/chat /ws/chat/room1 /ws/unknown; do
  printf '%s ' $p; curl -s -o /dev/null -w '%{http_code}\n' -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' localhost:8080$p
done
# → /ws/echo 401, /ws/chat 401, /ws/chat/room1 401, /ws/unknown 404
```

## 6. 인증 없는 공개 push

`/ws/public/push`는 토큰이나 티켓 없이 연결된다. 기본 토픽(`ticker`)만 1초마다 받는 읽기 전용 엔드포인트다.

```bash
sleep 3 | websocat ws://localhost:8080/ws/public/push | head -2
# → {"type":"push","topic":"ticker","seq":0,...,"server":"ws-backend@<host>:8090","payload":"xxx..."}

# 클라이언트 메시지는 거절된다(구독 변경 불가)
(echo '{"type":"subscribe","intervalMs":10}'; sleep 1.5) | websocat ws://localhost:8080/ws/public/push | grep error
# → {"type":"error","reason":"read only","server":"ws-backend@<host>:8090"}

# 인증 라우트는 그대로 401, 공개 라우트는 101
for p in /ws/public/push /ws/echo /ws/push /ws/chat /ws/unknown; do
  printf '%s ' $p; curl -s -o /dev/null -m 2 -w '%{http_code}\n' -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' localhost:8080$p
done
# → /ws/public/push 101, /ws/echo 401, /ws/push 401, /ws/chat 401, /ws/unknown 404
```

인증 여부는 두 곳에서 정한다(decisions D20).
- 게이트웨이: 라우트에 `filters: [WsAuth]`가 있으면 인증한다. 없으면 인증하지 않는다. 새 라우트를 추가할 때 인증이 필요하면 반드시 선언한다.
- 서비스: `@WsEndpoint("/ws/...", auth = WsEndpoint.Auth.NONE)`이면 익명으로 연다. 기본값은 인증 필요다.

게이트웨이에서 WsAuth를 빠뜨려도 서비스의 인증 엔드포인트는 열리지 않는다(fail-closed). 다만 클라이언트에는 401 대신 핸드셰이크 실패로 보인다.

## 7. direct 경로 비교

서비스를 `direct` 프로필(ws-backend 8092, ws-chat 8093)로 띄운 뒤 **포트만 바꿔** 같은 명령을 쓴다.

```bash
echo '{"type":"echo","id":"d1"}' | websocat -n -1 ws://localhost:8092/ws/echo -H "Authorization: Bearer $TOKEN"
websocat ws://localhost:8093/ws/chat -H "Authorization: Bearer $TOKEN"
```

direct 서비스는 자기 엔드포인트만 가진다. 예를 들어 8093에 `/ws/echo`로 연결하면, 인증은 통과하지만 핸들러가 없어 404다.

## 8. 자동 스모크 테스트 (websocat 불필요)

JDK만으로 한 번에 확인한다. 주소마다 그 주소가 제공하는 서비스만 검사한다(핸드셰이크가 404면 건너뛴다).
- ws-backend: echo, push, 티켓 재사용 401, 미인증 401, 응답 `server`가 `ws-backend@`
- ws-chat: 두 연결의 join → say 전달, 미인증 401, 응답 `server`가 `ws-chat@`
- 공개 push(`/ws/public/push`가 101일 때만): 자격증명 없이 push 수신, 클라이언트 메시지는 `read only`
- 라우트가 없는 `/ws/unknown` → 404

```bash
java scripts/WsSmoke.java http://localhost:8070 ws://localhost:8080                                             # relay만(두 서비스 + 라우팅)
java scripts/WsSmoke.java http://localhost:8070 ws://localhost:8080 ws://localhost:8092 ws://localhost:8093     # relay + direct 둘
# → ALL OK
```

## 9. 세션 로그 보기

ws-backend, ws-chat, ws-gateway는 세션이 열리고 닫힐 때 `ws.session` 로거로 한 줄씩 남긴다(decisions D21). 아래는 smoke 실행 중 실제 출력이다(id 일부 생략).

```text
# gateway
ws relay opened id=…-00000001-… route=ws-backend path=/ws/echo user=smoke-user upstream=localhost:8090 remote=127.0.0.1:62158 active=1
ws relay opened id=…-00000002-… route=ws-chat path=/ws/chat user=smoke-user upstream=localhost:8091 remote=127.0.0.1:62159 active=2
ws relay closed id=…-00000001-… route=ws-backend path=/ws/echo user=smoke-user upstream=localhost:8090 code=1000 reason=bye durationMs=… active=1
# ws-backend
ws session opened id=… path=/ws/echo user=smoke-user anonymous=false remote=127.0.0.1:62162 active=2
ws session closed id=… path=/ws/echo user=smoke-user code=1000 reason=bye closedBy=client durationMs=… active=1
```

- `active`는 그 프로세스의 현재 세션 수다. backend는 엔드포인트 합계, gateway는 relay 중인 클라이언트 수다.
- `closedBy=server`이면 서버가 닫았다(예: `code=3000 reason=token expired`, `code=1001`). 익명 엔드포인트는 `user=anonymous anonymous=true`, gateway에서는 `user=-`로 나온다.
- relay에서 클라이언트가 close 프레임 없이 끊으면 gateway는 1006, backend는 1002로 기록된다(SCG 변환, D14).
- 부하 실험에서는 끈다: `--logging.level.ws.session=WARN`

```bash
./gradlew :modules:ws-gateway:bootRun --args='--logging.level.ws.session=WARN'
```

## 10. 참고: 서버가 연결을 닫는 경우

모두 RFC 6455 §7.4 기준 코드다. 이 범위의 코드만 Spring Cloud Gateway를 거쳐도 그대로 전달된다(decisions D13, D14).

| close code | RFC 6455 근거 | 의미 | 클라이언트 권장 동작 |
|---|---|---|---|
| 3000 | §7.4.2 애플리케이션 범위 (IANA 이름 Unauthorized) | 토큰 만료. access token 만료 시각에 닫힌다. 티켓으로 연결해도 원래 access token 기준이다 | 토큰을 재발급한 뒤 재연결 |
| 3008 | §7.4.2 애플리케이션 범위 (IANA 이름 Timeout) | heartbeat 타임아웃. 서버 ping에 pong이 연속 2회 없으면 닫힌다. websocat은 pong을 자동으로 보내므로 유지된다 | 재연결 |
| 1001 | §7.4.1 Going Away | 서버 종료/배포(drain). ws-backend, ws-chat, gateway에 SIGTERM이 오면 20초 window 안에 지터를 두고 닫힌다 | 백오프와 지터를 두고 재연결 |

relay 경로에서 backend가 비정상 종료되면(프로세스 강제 종료 등), 클라이언트는 코드 없는 close 프레임(1005)을 받는다. direct 경로에서는 같은 상황이 close 프레임 없는 연결 끊김(1006)으로 보인다.

핸드셰이크 인증 실패는 close code가 아니라 업그레이드 전에 HTTP 401로 응답한다.

이 종료 코드들은 자동 테스트로 검증했다(`SelfModeTest`, `HeartbeatTest`, `DrainTest`, `GatewayE2ETest`). 세션 수명 관리는 ws-backend-core 공통이라 ws-chat도 같다. websocat 1.14.1은 close code를 보여 주지 않는다. `-v`를 붙여도 `Received WebSocket close message`만 출력된다. gateway에 SIGTERM을 보내 확인했으며, 20초 window 안에서 세션이 닫히는 것까지는 보인다.
