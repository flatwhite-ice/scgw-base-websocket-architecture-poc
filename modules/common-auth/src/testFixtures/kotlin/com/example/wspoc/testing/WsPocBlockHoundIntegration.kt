package com.example.wspoc.testing

import reactor.blockhound.BlockHound
import reactor.blockhound.integration.BlockHoundIntegration

/**
 * Allow-list for blocking calls that are one-time class initialisation, not runtime blocking.
 * Each entry must say why; anything not listed here still fails tests when it blocks an event loop.
 */
class WsPocBlockHoundIntegration : BlockHoundIntegration {
    override fun applyTo(builder: BlockHound.Builder) {
        // Static init discovers ContextInjector services via ServiceLoader (reads jar entries) on first use of
        // any kotlinx.coroutines.reactive await/asFlow — typically on a Netty event loop during the first request.
        builder.allowBlockingCallsInside("kotlinx.coroutines.reactive.ReactiveFlowKt", "<clinit>")
    }
}
