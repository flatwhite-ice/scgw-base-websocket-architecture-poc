// 여러 WebSocket 서비스(ws-backend, ws-chat)가 함께 쓰는 런타임 라이브러리(D17).
// 실행 가능한 앱이 아니므로 bootJar는 만들지 않는다. 빈은 AutoConfiguration으로 등록된다.
plugins {
    id("wspoc.spring-library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":modules:auth-token"))
    api(project(":modules:ws-metrics"))
    // 게이트웨이와 함께 쓰는 부품(drain 상태, 핸드셰이크 자격증명, close 상태, 로그 표기, access log)(D32).
    api(project(":modules:ws-server-support"))
    api(libs.spring.boot.starter.webflux)
    // 이 런타임 위의 서비스는 모두 /actuator/prometheus로 공통 메트릭을 내보낸다(D27).
    api(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    api(libs.kotlinx.coroutines.reactor)
    api(libs.jackson.module.kotlin)
    implementation(libs.kotlin.logging)

    testImplementation(testFixtures(project(":modules:auth-token")))

    // 서비스 모듈의 테스트와 ws-gateway의 E2E 테스트가 함께 쓴다.
    testFixturesApi(project(":modules:auth-token"))
    testFixturesApi(libs.spring.boot.starter.webflux)
    testFixturesApi(libs.kotlinx.coroutines.reactor)
    testFixturesApi(libs.jackson.module.kotlin)
    // 공용 테스트 구성(@SelfModeWsTest, TestKeySourceConfig)이 Spring Boot 테스트 지원을 쓴다.
    testFixturesApi(libs.spring.boot.starter.webflux.test)
}
