import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
    id("me.champeau.gradle.japicmp") version "0.4.6"
}

val apiBaselineVersion =
    providers
        .environmentVariable("API_BASELINE_VERSION")
        .orElse(providers.gradleProperty("apiBaselineVersion"))
val apiBaseline = configurations.create("apiBaseline")

dependencies {
    if (apiBaselineVersion.isPresent) {
        add(apiBaseline.name, "${project.group}:orchestra-api:${apiBaselineVersion.get()}")
    }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            artifactId = "orchestra-api"
            pom {
                name = "Orchestra API"
                description = "Public extension contracts for Orchestra"
                url = "https://github.com/IanTapply22/Orchestra"
            }
        }
    }
}

tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("apiCompatibility") {
    group = "verification"
    description = "Checks the public API against API_BASELINE_VERSION when configured."
    onlyIf("API_BASELINE_VERSION is configured") { apiBaselineVersion.isPresent }
    dependsOn(tasks.jar)
    oldClasspath.from(apiBaseline)
    newClasspath.from(tasks.jar)
    packageIncludes = listOf("com.iantapply.orchestra.api.*", "com.iantapply.orchestra.domain.*", "com.iantapply.orchestra.port.*")
    onlyModified = true
    failOnModification = true
    failOnSourceIncompatibility = true
    ignoreMissingClasses = true
    htmlOutputFile =
        layout.buildDirectory
            .file("reports/api-compatibility.html")
            .get()
            .asFile
}

tasks.check {
    dependsOn(tasks.named("apiCompatibility"))
}
