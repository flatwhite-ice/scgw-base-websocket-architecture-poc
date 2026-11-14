package com.example.wspoc.wscore

import com.example.wspoc.wscore.session.CloseCodes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 서버의 모든 close code가 RFC 6455 §7.4 기준이라는 규칙을 지킨다. 이 집합은 Spring Cloud Gateway 5.0.x가
 * 그대로 중계하는 집합(WebsocketRoutingFilter.adaptCloseStatus)과 정확히 같다. 그 외 코드(예: IANA에만 있는
 * 1012–1014)는 relay 클라이언트에 1002 Protocol error로 도착한다. docs/decisions.md D13/D14 참고.
 */
class CloseCodesRfc6455Test {
    /** Close 프레임에 실을 수 있는 §7.4.1 코드(1004는 예약, 1005/1006/1015는 "MUST NOT be set"). */
    private val rfc6455Defined = setOf(1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011)

    /** §7.4.2: 3000–3999 라이브러리/프레임워크/앱용(IANA 등록), 4000–4999 private use. */
    private val rfc6455ApplicationRange = 3000..4999

    @Test
    fun `every server close code is valid per RFC 6455 section 7_4`() {
        CloseCodes.ALL.forEach { status ->
            assertTrue(
                status.code in rfc6455Defined || status.code in rfc6455ApplicationRange,
                "close code ${status.code} is not an RFC 6455 code: SCG would relay it as 1002",
            )
        }
    }
}
