plugins {
    id("wspoc.spring-app-conventions")
}

dependencies {
    implementation(project(":modules:ws-backend-core"))

    testImplementation(testFixtures(project(":modules:ws-backend-core")))
}
