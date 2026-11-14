plugins {
    id("wspoc.spring-app-conventions")
}

dependencies {
    implementation(project(":modules:common-auth"))
    implementation(libs.spring.cloud.starter.gateway.server.webflux)
    implementation(libs.spring.cloud.starter.loadbalancer)

    // E2E 테스트는 같은 JVM에서 실제 ws-backend(relay 모드)를 게이트웨이 뒤에 띄운다.
    testImplementation(project(":modules:ws-backend"))
    testImplementation(testFixtures(project(":modules:ws-backend")))
}
