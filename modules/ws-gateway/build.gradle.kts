plugins {
    id("wspoc.spring-app-conventions")
}

dependencies {
    implementation(project(":modules:auth-token"))
    implementation(project(":modules:ws-metrics"))
    // /actuator/prometheus로 공통 메트릭을 내보낸다(D27).
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.cloud.starter.gateway.server.webflux)
    implementation(libs.spring.cloud.starter.loadbalancer)
    implementation(libs.kotlin.logging)

    // E2E 테스트는 같은 JVM에서 실제 ws-backend와 ws-chat(relay 모드)을 게이트웨이 뒤에 띄운다.
    testImplementation(project(":modules:ws-backend-service-default"))
    testImplementation(project(":modules:ws-backend-service-chat"))
    testImplementation(testFixtures(project(":modules:ws-backend-core")))
}
