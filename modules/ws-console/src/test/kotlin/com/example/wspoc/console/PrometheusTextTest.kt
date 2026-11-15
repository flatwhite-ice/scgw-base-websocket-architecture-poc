package com.example.wspoc.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrometheusTextTest {
    @Test
    fun `parses samples with and without labels`() {
        val samples =
            PrometheusText.parse(
                """
                # HELP ws_sessions_active 지금 열려 있는 WebSocket 세션 수
                # TYPE ws_sessions_active gauge
                ws_sessions_active{application="ws-gateway",route="ws-chat"} 3.0
                process_uptime_seconds 12.5
                ws_handshake_seconds_bucket{le="+Inf",result="ok"} 7 1700000000000
                jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.50994944E8
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                MetricSample("ws_sessions_active", mapOf("application" to "ws-gateway", "route" to "ws-chat"), 3.0),
                MetricSample("process_uptime_seconds", emptyMap(), 12.5),
                MetricSample("ws_handshake_seconds_bucket", mapOf("le" to "+Inf", "result" to "ok"), 7.0),
                MetricSample("jvm_memory_used_bytes", mapOf("area" to "heap", "id" to "G1 Eden Space"), 1.50994944E8),
            ),
            samples,
        )
    }

    @Test
    fun `unescapes label values and keeps braces inside them`() {
        val sample = PrometheusText.parseLine("""m{a="x\"y",b="c\\d",c="{k=1}, z"} 1""")
        assertEquals(mapOf("a" to "x\"y", "b" to "c\\d", "c" to "{k=1}, z"), sample?.labels)
    }

    @Test
    fun `skips non-finite values and malformed lines`() {
        assertNull(PrometheusText.parseLine("m NaN"))
        assertNull(PrometheusText.parseLine("m{a=\"b\"} +Inf"))
        assertNull(PrometheusText.parseLine("m{a=\"b\" 1"))
        assertNull(PrometheusText.parseLine("m{a=b} 1"))
        assertNull(PrometheusText.parseLine("just-a-name"))
        assertNull(PrometheusText.parseLine(""))
    }

    @Test
    fun `snapshot json escapes strings and keeps numbers plain`() {
        val sample = MetricSample("m", mapOf("le" to "+Inf", "q" to "a\"b\\c\n"), 1.5E8)
        val json = MetricsSnapshot(7, 2000, listOf(TargetMetrics("t", "http://x", false, "timeout", 3, listOf(sample)))).toJson()
        assertEquals(
            """{"timestamp":7,"refreshMillis":2000,"targets":[{"name":"t","url":"http://x","up":false,"error":"timeout",""" +
                """"scrapeMillis":3,"samples":[{"name":"m","labels":{"le":"+Inf","q":"a\"b\\c\u000a"},"value":1.5E8}]}]}""",
            json,
        )
    }
}
