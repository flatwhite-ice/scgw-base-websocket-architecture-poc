// WebSocket 모듈(ws-backend-core, ws-gateway)이 함께 쓰는 메트릭 정의(D27). Spring에 의존하지 않는다.
// 이름과 태그를 한곳에 두어 서비스와 게이트웨이의 수치를 같은 쿼리로 비교할 수 있게 한다.
plugins {
    id("wspoc.kotlin-conventions")
    `java-library`
}

dependencies {
    api(libs.micrometer.core)
}
