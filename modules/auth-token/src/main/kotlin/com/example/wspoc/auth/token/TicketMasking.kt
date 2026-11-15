package com.example.wspoc.auth.token

/** URI나 쿼리 문자열의 WS 티켓 값을 가려서 로그에 남지 않게 한다. */
object TicketMasking {
    private val ticketParam = Regex("""([?&]ticket=)[^&#\s]*""")

    fun mask(uri: CharSequence): String = ticketParam.replace(uri) { "${it.groupValues[1]}***" }
}
