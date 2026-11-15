plugins {
    id("wspoc.kotlin-conventions")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.nimbus.jose.jwt)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.caffeine)
    testImplementation(libs.reactor.netty.http)

    // 공용 BlockHound 허용 목록. 모든 모듈의 테스트가 ServiceLoader로 읽어 간다.
    testFixturesApi(libs.blockhound)
}
