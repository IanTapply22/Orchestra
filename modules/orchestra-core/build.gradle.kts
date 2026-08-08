plugins {
    `java-library`
}

dependencies {
    api(project(":orchestra-api"))
    testImplementation(project(":orchestra-adapter-memory"))
    testImplementation(libs.archunit)
}
