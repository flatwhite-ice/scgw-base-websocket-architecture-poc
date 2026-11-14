# CLI로 WebSocket 테스트하기 (websocat)

게이트웨이(relay)와 direct 경로에 WebSocket을 연결해 수동으로 확인하는 명령어 모음이다.
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
| auth-api | 8080 | `./gradlew :modules:auth-api:bootRun` |
| ws-backend (relay, 기본값) | 8081 | `./gradlew :modules:ws-backend:bootRun` |
| ws-gateway | 8082 | `./gradlew :modules:ws-gateway:bootRun` |
| ws-backend (direct, 선택) | 8083 | `./gradlew :modules:ws-backend:bootRun --args='--spring.profiles.active=direct'` |

- 경로 구성
  - relay: 클라이언트 → **8082(gateway)** → 8081(backend)
  - direct: 클라이언트 → **8083(backend)**
- relay backend(8081)는 게이트웨이가 넣어 준 헤더를 신뢰한다. 따라서 클라이언트는 항상 8082로 접속한다.

## 2. 토큰과 티켓 발급

```bash
# access token: 유효 15분, 여러 번 사용 가능
TOKEN=$(curl -s -X POST localhost:8080/auth/token \
  -H 'content-type: application/json' -d '{"userId":"u1"}' | jq -r .token)

# WS 티켓: 유효 30초, 1회용. 연결할 때마다 새로 발급한다
TICKET=$(curl -s -X POST localhost:8080/auth/ws-ticket \
  -H "authorization: Bearer $TOKEN" | jq -r .ticket)
```

인증 방법은 두 가지다.

| 방식 | 사용법 | 특징 |
|---|---|---|
| Bearer 헤더 | `-H "Authorization: Bearer $TOKEN"` | 반복 테스트에 편하다 |
| 티켓 쿼리 | `?ticket=$TICKET` | 브라우저와 같은 방식이다. 브라우저는 WS 요청에 헤더를 못 넣는다 |

## 3. 대화형 연결

```bash
websocat ws://localhost:8082/ws/echo -H "Authorization: Bearer $TOKEN"
# 또는
websocat "ws://localhost:8082/ws/echo?ticket=$TICKET"
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

- 형식은 `<앱 이름>@<호스트>:<실제 포트>`이다. 예: `ws-backend@flatwhite-mbp.local:8081`
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
  | websocat -n -1 ws://localhost:8082/ws/echo -H "Authorization: Bearer $TOKEN"
# → {"type":"echo","id":"1","ts":0,"payload":"hello","srvTs":...,"userId":"u1","server":"ws-backend@<host>:8081"}

# echo (티켓)
TICKET=$(curl -s -X POST localhost:8080/auth/ws-ticket -H "authorization: Bearer $TOKEN" | jq -r .ticket)
echo '{"type":"echo","id":"2","payload":"via-ticket"}' \
  | websocat -n -1 "ws://localhost:8082/ws/echo?ticket=$TICKET"
# → {"type":"echo","id":"2","payload":"via-ticket","srvTs":...,"userId":"u1","server":"ws-backend@<host>:8081"}

# push 수신: stdin을 열어 둔 채 몇 개만 받기
sleep 3 | websocat ws://localhost:8082/ws/push -H "Authorization: Bearer $TOKEN" | head -2
# → {"type":"push","topic":"ticker","seq":0,...,"server":"ws-backend@<host>:8081",...}
#   {"type":"push","topic":"ticker","seq":1,...}

# subscribe 파라미터 변경 후 스트림 받기
{ echo '{"type":"subscribe","topic":"fast","intervalMs":200,"payloadBytes":8}'; sleep 1; } \
  | websocat ws://localhost:8082/ws/echo -H "Authorization: Bearer $TOKEN" | head -3
# → {"type":"push","topic":"fast","seq":0,...,"payload":"xxxxxxxx"} ... (seq 0,1,2)
```

`head`가 먼저 끝나면서 나오는 `Broken pipe` 메시지는 무시해도 된다.

### 4.2 인증과 보안 검증

```bash
# 티켓 재사용 → 401 (위에서 이미 쓴 $TICKET으로 다시 연결)
echo '{"type":"echo","id":"3"}' | websocat -n -1 "ws://localhost:8082/ws/echo?ticket=$TICKET"
# → websocat: WebSocketError: ... Received unexpected status code (401 Unauthorized)

# 자격증명 없음 → 401
websocat -1 ws://localhost:8082/ws/echo </dev/null
# → ... (401 Unauthorized)

# 헤더 스푸핑은 무시된다: X-User-Id: evil을 보내도 토큰의 사용자(u1)로 처리된다
TICKET=$(curl -s -X POST localhost:8080/auth/ws-ticket -H "authorization: Bearer $TOKEN" | jq -r .ticket)
echo '{"type":"echo","id":"spoof"}' \
  | websocat -n -1 "ws://localhost:8082/ws/echo?ticket=$TICKET" -H "X-User-Id: evil"
# → {"type":"echo","id":"spoof",...,"userId":"u1","server":"ws-backend@<host>:8081"}
```

### 4.3 curl로 업그레이드 전 401 확인

websocat 없이 상태 코드만 본다. 인증 실패는 업그레이드(101) 전에 401로 거절된다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  localhost:8082/ws/echo
# → 401
```

## 5. direct 경로 비교

backend를 `direct` 프로필(8083)로 띄운 뒤 **포트만 바꿔** 같은 명령을 쓴다.

```bash
echo '{"type":"echo","id":"d1"}' | websocat -n -1 ws://localhost:8083/ws/echo -H "Authorization: Bearer $TOKEN"
```

## 6. 자동 스모크 테스트 (websocat 불필요)

JDK만으로 echo, push, 티켓 재사용 401, 미인증 401을 한 번에 확인한다.

```bash
java scripts/WsSmoke.java http://localhost:8080 ws://localhost:8082                       # relay만
java scripts/WsSmoke.java http://localhost:8080 ws://localhost:8082 ws://localhost:8083   # relay + direct
# → ALL OK
```

## 7. 참고: 서버가 연결을 닫는 경우

모두 IANA WebSocket Close Code 레지스트리에 등록된 코드다.

| close code | IANA 이름 | 의미 | 클라이언트 권장 동작 |
|---|---|---|---|
| 3000 | Unauthorized | 토큰 만료. access token 만료 시각에 닫힌다. 티켓으로 연결해도 원래 access token 기준이다 | 토큰을 재발급한 뒤 재연결 |
| 3008 | Timeout | heartbeat 타임아웃. 서버 ping에 pong이 연속 2회 없으면 닫힌다. websocat은 pong을 자동으로 보내므로 유지된다 | 재연결 |
| 1012 | Service Restart | 서버 종료/배포(drain). SIGTERM 후 20초 window 안에 지터를 두고 닫힌다 | 백오프와 지터를 두고 재연결 |

핸드셰이크 인증 실패는 close code가 아니라 업그레이드 전에 HTTP 401로 응답한다.

이 종료 코드들은 자동 테스트로 검증했다(`SelfModeTest`, `HeartbeatTest`, `DrainTest`, `GatewayE2ETest`). websocat 화면에서 close code를 확인하는 방법은 따로 검증하지 않았다.
