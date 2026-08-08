plugins {
    `java-library`
}

dependencies {
    implementation(project(":orchestra-api"))
    implementation(project(":orchestra-core"))
    api(libs.hikari)
    implementation(libs.postgresql)
    testImplementation(libs.h2)
}
