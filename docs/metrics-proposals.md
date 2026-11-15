# 추가 메트릭 제언 (M5 모니터링 콘솔)

모니터링 탭(D28)은 지금 있는 메트릭(D27)만으로 만들었다. 만드는 동안 "이 값이 있으면 화면이 더 정확해진다"고 느낀 것을 적는다.
아직 구현하지 않았다. 채택 여부는 따로 정한다. 태그는 모두 D27의 규칙(값의 개수가 한정된 것만)을 지킨다.

## 화면에서 실제로 아쉬웠던 것

| 제안 | 형태 | 지금 화면의 한계 | 비용 |
|---|---|---|---|
| `ws.session.duration`에 히스토그램 버킷 | 기존 Timer에 SLO 버킷(1초–1시간) | count와 sum만 있어 평균밖에 못 낸다. 그래서 화면에 세션 수명을 넣지 않았다. E4(재연결)에서는 "곧바로 끊긴 세션"의 비율이 필요하다 | 세션당 1회 기록. 시계열은 scope × 버킷 수만큼 늘어난다 |
| `ws.drain.active` | Gauge(0/1) | drain 중인지 직접 알 수 없다. `ws.handshake{result=draining}`이나 `ws.close{code=1001}`이 늘어난 뒤에야 짐작한다 | 없음에 가깝다 |
| `ws.sessions.active`에 upstream 인스턴스 태그(게이트웨이) | 기존 Gauge에 `instance` 태그, 또는 별도 Gauge `ws.relay.upstream.sessions` | 게이트웨이가 어느 backend 인스턴스로 몇 개를 보냈는지 모른다. 라운드로빈 쏠림(E1, E4 후속 과제)을 화면에서 확인할 수 없다 | 태그 값이 파드 수만큼이다. 파드가 자주 바뀌면 시계열이 쌓이므로 끝난 인스턴스의 미터는 지워야 한다 |
| 빌드와 모드 정보 `ws.info` | Gauge(값 1), 태그 `auth_mode`(gateway/self), `version` | 대상이 relay 모드인지 direct 모드인지 메트릭으로는 알 수 없다. 지금은 `route` label이 있으면 게이트웨이라고 추정한다 | 없음 |
| auth-api에 actuator | `/actuator/prometheus` 노출 | 프로세스 카드에 auth-api가 없다. 토큰·티켓 발급 지연과 JWKS 조회가 보이지 않는다 | 의존성 2개와 설정 |

## 실험(E1–E5)에서 필요해질 것

| 제안 | 형태 | 이유 | 비용 |
|---|---|---|---|
| `ws.bytes` | Counter, scope × `direction` | `ws.messages`는 건수라 payload 크기가 다른 실험(E2)을 비교할 수 없다. 대역폭은 건수가 아니라 바이트로 봐야 한다 | 메시지마다 Counter 증가 1회(길이는 이미 알고 있다) |
| `ws.heartbeat.rtt` | Timer(히스토그램), scope | ping을 보내고 pong을 받기까지의 시간이다. 서버에서 재는 유일한 왕복 지연이고, 메시지 단위가 아니라 heartbeat 주기마다 1회라 싸다. `ws.message.relay.latency`를 만들지 않은 자리(D27)를 일부 메운다 | 세션당 heartbeat 주기마다 1회 |
| `ws.send.queue.full` 또는 큐 사용률 | Gauge(큐가 찬 세션 수) 또는 DistributionSummary | `ws.messages.dropped`는 넘친 뒤에야 오른다. 넘치기 전의 압력을 볼 수 없다 | Gauge는 싸다. 분포는 메시지마다 기록하면 비싸므로 heartbeat 주기에 표본만 뜬다 |
| `ws.chat.rooms`, `ws.chat.room.members` | Gauge(방 수), DistributionSummary(방당 인원) | 채팅의 부하는 세션 수가 아니라 fan-out(방당 인원)이 정한다. 방 이름은 태그로 쓰지 않는다 | join/leave 때만 갱신 |
| `ws.auth.jwks.refresh` | Counter, `result` | D27에서 뺐다. direct 모드에서 키 회전이나 auth-api 장애가 401로 번지는 것을 `reason=unknown_key`로만 본다 | 조회마다 1회 |
| `ws.auth.replay.cache.size` | Gauge | 티켓 재사용 방지 캐시(Caffeine)가 연결 폭주 때 얼마나 커지는지 본다(E1 메모리 해석) | 없음에 가깝다 |

## 메트릭이 아니라 화면 쪽에서 풀 수 있는 것

- 중계 지연: 콘솔이 relay와 direct에 echo를 주기적으로 보내 왕복 시간 차이를 그릴 수 있다. D27이 k6에 맡긴 것과 같은 방식이고, 서버 메트릭은 필요 없다. 브라우저 한 개의 값이라 부하 실험의 대체는 아니다.
- 기록 보존: 지금은 브라우저가 열려 있는 동안만 기록이 남는다. Prometheus가 붙으면(M6) 콘솔이 Prometheus를 조회하도록 바꾸는 편이 낫다. 콘솔 서버에 저장소를 만드는 것은 권하지 않는다.
