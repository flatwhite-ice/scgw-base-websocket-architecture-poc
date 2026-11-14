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

    // Shared BlockHound allow-list, picked up via ServiceLoader by every module's tests.
    testFixturesApi(libs.blockhound)
}
