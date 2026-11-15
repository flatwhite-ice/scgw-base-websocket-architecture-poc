package com.example.wspoc.testing

import reactor.blockhound.BlockHound
import reactor.blockhound.integration.BlockHoundIntegration

/**
 * 런타임 블로킹이 아니라 1회성 클래스 초기화인 블로킹 호출의 허용 목록.
 * 항목마다 이유를 적어야 한다. 여기 없는 호출이 event loop를 막으면 테스트는 여전히 실패한다.
 */
class WsPocBlockHoundIntegration : BlockHoundIntegration {
    override fun applyTo(builder: BlockHound.Builder) {
        // 정적 초기화가 ServiceLoader로 ContextInjector 서비스를 찾는다(jar 항목 읽기). kotlinx.coroutines.reactive의
        // await/asFlow를 처음 쓸 때 실행되며, 보통 첫 요청 중 Netty event loop에서 일어난다.
        builder.allowBlockingCallsInside("kotlinx.coroutines.reactive.ReactiveFlowKt", "<clinit>")
        // kotlin-reflect가 내장 타입 메타데이터(.kotlin_builtins)를 jar에서 처음 한 번 읽는다(이후 메모이즈). WebFlux가
        // suspend 컨트롤러를 처음 호출할 때 event loop에서 일어날 수 있다(시작 시 다른 리플렉션이 먼저 읽지 않은 경우, 예: ws-console).
        builder.allowBlockingCallsInside(
            "kotlin.reflect.jvm.internal.impl.builtins.jvm.JvmBuiltInsPackageFragmentProvider",
            "findPackage",
        )
    }
}
