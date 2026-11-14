# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This is a WebSocket gateway PoC that is just getting started. Right now the repo is a single-module Spring Initializr skeleton: `com.example.websocketpoc.WebsocketPocApplication`, with Boot 4.1.2, Kotlin 2.3.21, JDK 25, Spring Cloud 2025.1.4 and Gradle 9.8.2.

**`docs/handoff/handoff.md` (in Korean) is the spec for the whole PoC. Read all of it before starting work.** It defines the target architecture, the milestones (M0–M6), the experiments (E1–E5) and the coding rules. Work through the milestones in order, and a milestone's completion criteria must all pass before you start the next one. The handoff doc's first instructions are:
1. Create `docs/progress.md` with the milestone checklist.
2. Confirm the dependency versions and record them in `docs/decisions.md`. Pin them in `gradle/libs.versions.toml`.
3. Start M0, which converts the skeleton into a Gradle multi-module build under `modules/`.

The Initializr skeleton currently pulls in `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test`. These conflict with the spec's "WebFlux + Reactor Netty only, no MVC" rule and should be removed during M0.

## Commands

```bash
./gradlew build                      # compile + test; must pass before every commit
./gradlew test                       # all tests
./gradlew test --tests 'com.example.websocketpoc.WebsocketPocApplicationTests'   # single class
./gradlew test --tests '*ClassName.methodName'                                   # single method
./gradlew bootRun                    # run the app
```

After the M0 multi-module conversion, prefix tasks with the module path, e.g. `./gradlew :modules:ws-backend:test`.

## Target architecture (from handoff.md)

The PoC compares two ways of putting WebSocket traffic in front of a service on 1 vCPU / 4 GB pods:
- **A. Relay:** Client → `ws-gateway` (Spring Cloud Gateway, JWT check) → `ws-backend`. Each session uses two connections.
- **B. Direct:** Client → Ingress/L4 → `ws-backend`, which verifies the JWT during the handshake.

Modules:
- `common-auth`: RS256 JWT issue/verify with nimbus-jose-jwt and a cached JWKS. It keeps Spring dependencies to a minimum and must be non-blocking. Verification produces `AuthPrincipal(userId, scopes, expiresAt)`.
- `auth-api` (port 8080): `/auth/token`, `/auth/ws-ticket` (single-use, 30 s TTL, tracked in Caffeine) and `/.well-known/jwks.json`.
- `ws-backend` (port 8081): `/ws/echo` and `/ws/push`. `ws.auth.mode=gateway` trusts `X-User-Id`; `ws.auth.mode=self` verifies `?ticket=` or `Authorization`. It also handles heartbeat ping/pong, closes with `4401` when the token expires, and does a graceful drain (close `1001` within `ws.drain.window`).
- `ws-gateway` (port 8082): an SCG route `/ws/** → lb:ws://ws-backend` plus a `WsAuthFilter` GlobalFilter. The filter strips client-supplied `X-User-Id`/`X-Scopes` headers. Backends are reached by pod IP, with ClusterIP mode available behind a property switch for experiment E3.

Invariants that matter for the experiments:
- Auth failures are rejected with **HTTP 401 before the upgrade**. No upstream connection may be opened for them.
- `?ticket=` must be masked in access logs.
- The metric names in handoff §5.5 (`ws.sessions.active`, `ws.handshake`, `ws.messages`, `ws.close`, …) are what the dashboards and experiments rely on.

The planned layout also includes `loadtest/k6/`, `deploy/compose/`, `deploy/grafana/` and `deploy/k8s/{base,overlays/relay,overlays/direct}`.

## Coding rules (from handoff §9)

- Never block the event loop. Tests enforce this with BlockHound. If something has to block, isolate it on `Dispatchers.IO` or `boundedElastic` and add a comment explaining why.
- Write handlers as `suspend` functions and `Flow`, and convert to and from Reactor types only at the boundaries.
- Put configuration in `@ConfigurationProperties` data classes, with no hardcoded values.
- Use structured JSON logs. Carry session ID and userId in the Reactor Context, not MDC. **Don't log at INFO per connection or per message**, because that distorts the load tests.
- Don't make improvements outside the current milestone's scope. Record them under "후속 과제" (follow-ups) in `docs/progress.md`.
- When the spec doesn't cover a decision, make a reasonable choice and record the reasoning and alternatives in `docs/decisions.md`.
- SCG artifact names and property prefixes differ between versions, so check them against the Spring Cloud version actually in use.
