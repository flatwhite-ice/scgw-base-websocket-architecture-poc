package com.example.wspoc.auth.common

import kotlin.test.Test
import kotlin.test.assertEquals

class TicketMaskingTest {
    @Test
    fun `masks ticket in any position and keeps other params`() {
        assertEquals("/ws/echo?ticket=***", TicketMasking.mask("/ws/echo?ticket=eyJ.abc.def"))
        assertEquals("/ws/echo?a=1&ticket=***&b=2", TicketMasking.mask("/ws/echo?a=1&ticket=eyJ.abc.def&b=2"))
        assertEquals("/ws/echo?myticket=x", TicketMasking.mask("/ws/echo?myticket=x"))
        assertEquals("/ws/echo", TicketMasking.mask("/ws/echo"))
    }
}
