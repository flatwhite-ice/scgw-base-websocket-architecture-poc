package com.example.wspoc.backend

import com.example.wspoc.backend.endpoint.WsEndpoint
import com.example.wspoc.backend.endpoint.WsEndpointHandlerMapping
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class WsEndpointHandlerMappingTest {
    open class NoopHandler : WebSocketHandler {
        override fun handle(session: WebSocketSession): Mono<Void> = Mono.empty()
    }

    @WsEndpoint("/ws/a", "/ws/a2")
    class ClassLevelHandler : NoopHandler()

    @Configuration(proxyBeanMethods = false)
    class Valid {
        @Bean
        fun classLevel() = ClassLevelHandler()

        @Bean
        @WsEndpoint("/ws/b")
        fun methodLevel() = NoopHandler()

        @Bean
        fun notAnEndpoint() = NoopHandler()
    }

    @Configuration(proxyBeanMethods = false)
    class Duplicate {
        @Bean
        @WsEndpoint("/ws/x")
        fun first() = NoopHandler()

        @Bean
        @WsEndpoint("/ws/x")
        fun second() = NoopHandler()
    }

    @Configuration(proxyBeanMethods = false)
    class NotAHandler {
        @Bean
        @WsEndpoint("/ws/y")
        fun text() = "not a handler"
    }

    @Configuration(proxyBeanMethods = false)
    class NoPath {
        @Bean
        @WsEndpoint
        fun handler() = NoopHandler()
    }

    private fun mappingOf(config: Class<*>) = AnnotationConfigApplicationContext(config).use { WsEndpointHandlerMapping.from(it).endpoints }

    @Test
    fun `collects class-level and bean-method annotations`() {
        AnnotationConfigApplicationContext(Valid::class.java).use { ctx ->
            val endpoints = WsEndpointHandlerMapping.from(ctx).endpoints
            assertEquals(setOf("/ws/a", "/ws/a2", "/ws/b"), endpoints.keys)
            assertSame<Any?>(ctx.getBean("classLevel"), endpoints["/ws/a"])
            assertSame<Any?>(ctx.getBean("classLevel"), endpoints["/ws/a2"])
            assertSame<Any?>(ctx.getBean("methodLevel"), endpoints["/ws/b"])
        }
    }

    @Test
    fun `duplicate path fails at startup`() {
        val e = assertFailsWith<IllegalStateException> { mappingOf(Duplicate::class.java) }
        assertContains(e.message!!, "/ws/x")
    }

    @Test
    fun `non-handler bean fails at startup`() {
        val e = assertFailsWith<IllegalStateException> { mappingOf(NotAHandler::class.java) }
        assertContains(e.message!!, "not a WebSocketHandler")
    }

    @Test
    fun `annotation without a path fails at startup`() {
        assertFailsWith<IllegalArgumentException> { mappingOf(NoPath::class.java) }
    }
}
