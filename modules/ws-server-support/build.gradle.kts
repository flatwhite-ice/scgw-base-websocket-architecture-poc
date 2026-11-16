// WebSocket 서버 프로세스(ws-backend-core 위의 서비스, ws-gateway)가 함께 쓰는 부품(D32).
// 게이트웨이는 core에 의존하지 않으므로(core의 AutoConfiguration이 게이트웨이에 붙으면 안 된다) 따로 뺐다.
// 빈을 스스로 등록하지 않는 일반 클래스와 함수만 둔다. 예외는 선택형 access log(AutoConfiguration) 하나다.
plugins {
    id("wspoc.spring-library-conventions")
}

dependencies {
    api(project(":modules:auth-token"))
    api(project(":modules:ws-metrics"))
    api(libs.spring.boot.starter.webflux)
    api(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlin.logging)

    testImplementation(testFixtures(project(":modules:auth-token")))
}
