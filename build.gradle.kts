// Root project only aggregates modules; conventions live in build-logic/.
group = "com.example"
version = "0.0.1-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}
