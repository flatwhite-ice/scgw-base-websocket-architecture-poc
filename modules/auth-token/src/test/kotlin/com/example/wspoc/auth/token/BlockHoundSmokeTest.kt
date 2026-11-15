package com.example.wspoc.auth.token

import reactor.blockhound.BlockingOperationError
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import kotlin.test.Test
import kotlin.test.assertTrue

class BlockHoundSmokeTest {
    @Test
    fun `blocking call on parallel scheduler is detected`() {
        val err =
            runCatching {
                Mono.fromCallable { Thread.sleep(10) }.subscribeOn(Schedulers.parallel()).block()
            }.exceptionOrNull()
        assertTrue(err?.cause is BlockingOperationError || err is BlockingOperationError, "got $err")
    }
}
