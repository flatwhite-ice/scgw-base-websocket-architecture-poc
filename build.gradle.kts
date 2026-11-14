// 루트 프로젝트는 모듈만 묶는다. 공통 규칙은 build-logic/에 있다.
group = "com.example"
version = "0.0.1-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}
