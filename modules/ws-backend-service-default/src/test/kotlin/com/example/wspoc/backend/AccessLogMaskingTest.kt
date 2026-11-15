package com.example.wspoc.backend

import com.example.wspoc.wscore.testing.TestKeys
import com.example.wspoc.wscore.testing.WsTestClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ws.auth.mode=self", "ws.auth.jwks-uri=", "ws.access-log.enabled=true"],
)
@Import(TestAuthConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class AccessLogMaskingTest(
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `access log masks the ticket`(output: CapturedOutput) =
        runBlocking {
            val ticket = TestKeys.ticket("masked")
            WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo?ticket=$ticket")).close()
            // 거절된 핸드셰이크도 로그에 남는다.
            runCatching { WsTestClient().connect(URI.create("ws://localhost:$port/ws/echo?ticket=$ticket")) }

            val deadline = System.currentTimeMillis() + 3_000
            while (!output.out.contains("/ws/echo?ticket=***") && System.currentTimeMillis() < deadline) delay(50)

            assertTrue(output.out.contains("/ws/echo?ticket=***"), "access log line expected")
            assertFalse(output.out.contains(ticket), "raw ticket leaked into logs")
        }
}
