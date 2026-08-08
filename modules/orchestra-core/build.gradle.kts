import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":orchestra-api"))
    testImplementation(project(":orchestra-adapter-memory"))
    testImplementation(libs.archunit)
}

publishing {
    publications {
        create<MavenPublication>("core") {
            from(components["java"])
            artifactId = "orchestra-core"
            pom {
                name = "Orchestra Core"
                description = "Platform-neutral Orchestra engine and services"
                url = "https://github.com/IanTapply22/Orchestra"
            }
        }
    }
}
