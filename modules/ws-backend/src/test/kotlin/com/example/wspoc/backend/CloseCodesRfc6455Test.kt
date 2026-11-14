package com.example.wspoc.backend

import com.example.wspoc.backend.session.CloseCodes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the rule that every server close code is valid per RFC 6455 §7.4. That is also exactly the set Spring
 * Cloud Gateway 5.0.x relays unchanged (WebsocketRoutingFilter.adaptCloseStatus); anything else (e.g. IANA-only
 * 1012–1014) reaches relay clients as 1002 Protocol error. See docs/decisions.md D13/D14.
 */
class CloseCodesRfc6455Test {
    /** §7.4.1 codes an endpoint may put in a Close frame (1004 reserved; 1005/1006/1015 "MUST NOT be set"). */
    private val rfc6455Defined = setOf(1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011)

    /** §7.4.2: 3000–3999 libraries/frameworks/apps (IANA-registered), 4000–4999 private use. */
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
