plugins {
    id("wspoc.kotlin-conventions")
    `java-library`
}

dependencies {
    api(libs.nimbus.jose.jwt)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.caffeine)
    testImplementation(libs.reactor.netty.http)
}
