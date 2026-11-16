package com.example.wspoc.backend

import com.example.wspoc.wscore.testing.SelfModeWsTest
import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 세션 열림/종료 로그(`ws.session`, D21): 세션 id, 사용자, 종료 코드와 주체, active 수를 남기고 티켓은 남기지 않는다. */
class SessionLogTest {
    @Nested
    @SelfModeWsTest
    @TestPropertySource(properties = ["ws.instance-id=session-log"])
    @ExtendWith(OutputCaptureExtension::class)
    inner class Enabled(
        @LocalServerPort val port: Int,
    ) {
        @Test
        fun `logs open and client close with the active session count`(output: CapturedOutput) =
            runBlocking {
                val ticket = TestKeys.ticket("log-alice")
                val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo?ticket=$ticket"))
                val opened =
                    output.awaitLine(
                        Regex("ws session opened id=(\\S+) path=/ws/echo user=log-alice anonymous=false .* active=(\\d+)"),
                    )
                val id = opened.groupValues[1]
                assertTrue(opened.groupValues[2].toInt() >= 1)

                conn.close()

                val closed =
                    output.awaitLine(
                        Regex(
                            "ws session closed id=$id path=/ws/echo user=log-alice code=(\\d+) .*closedBy=client durationMs=\\d+ active=\\d+",
                        ),
                    )
                assertNotNull(closed)
                assertFalse(output.out.contains(ticket), "ticket leaked into session logs")
            }

        @Test
        fun `server initiated close is logged with its code`(output: CapturedOutput) =
            runBlocking {
                val headers = HttpHeaders().apply { setBearerAuth(TestKeys.accessToken("log-bob", Duration.ofSeconds(1))) }
                val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo"), headers)

                assertEquals(3000, conn.awaitClose(Duration.ofSeconds(5)).code)
                output.awaitLine(
                    Regex("ws session closed id=\\S+ path=/ws/echo user=log-bob code=3000 reason=token expired closedBy=server"),
                )
                Unit // JUnit은 void가 아닌 테스트 메서드를 실행하지 않는다(runBlocking의 반환 타입이 메서드 타입이 된다).
            }

        @Test
        fun `anonymous sessions are logged as anonymous`(output: CapturedOutput) =
            runBlocking {
                val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/public/push"))
                output.awaitLine(Regex("ws session opened id=\\S+ path=/ws/public/push user=anonymous anonymous=true"))
                conn.close()
            }
    }

    @Nested
    @SelfModeWsTest
    @TestPropertySource(properties = ["logging.level.ws.session=WARN"])
    @ExtendWith(OutputCaptureExtension::class)
    inner class Disabled(
        @LocalServerPort val port: Int,
    ) {
        @Test
        fun `logging level WARN turns session logs off`(output: CapturedOutput) =
            runBlocking {
                val conn = WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo?ticket=${TestKeys.ticket("quiet")}"))
                conn.sendJson("type" to "echo", "id" to "1")
                conn.nextJson()
                conn.close()
                delay(500)

                assertFalse(output.out.contains("ws session"), "session logs must be off")
            }
    }
}

private suspend fun CapturedOutput.awaitLine(
    pattern: Regex,
    timeout: Duration = Duration.ofSeconds(3),
): MatchResult {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
        pattern.find(out)?.let { return it }
        check(System.nanoTime() < deadline) { "no log line matching $pattern" }
        delay(25)
    }
}
