package com.example.wspoc.auth.common

/** Masks WS ticket values in a URI/query string so they never end up in logs. */
object TicketMasking {
    private val ticketParam = Regex("""([?&]ticket=)[^&#\s]*""")

    fun mask(uri: CharSequence): String = ticketParam.replace(uri) { "${it.groupValues[1]}***" }
}
