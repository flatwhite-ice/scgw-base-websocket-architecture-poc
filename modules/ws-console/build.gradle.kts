plugins {
    id("wspoc.spring-app-conventions")
}

// 브라우저 테스트 콘솔(D23). 정적 화면 + auth-api 중계 + 서버 측 핸드셰이크 probe만 한다.
dependencies {
    implementation(libs.kotlin.logging)
}
