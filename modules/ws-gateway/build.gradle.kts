plugins {
    id("wspoc.spring-app-conventions")
}

dependencies {
    implementation(project(":modules:common-auth"))
    implementation(libs.spring.cloud.starter.gateway.server.webflux)
    implementation(libs.spring.cloud.starter.loadbalancer)
}
