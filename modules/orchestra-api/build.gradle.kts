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
        .map(String::trim)
        .filter(String::isNotEmpty)

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

if (apiBaselineVersion.isPresent) {
    val apiBaseline = configurations.create("apiBaseline")
    dependencies.add(apiBaseline.name, "${project.group}:orchestra-api:${apiBaselineVersion.get()}")

    val apiCompatibility =
        tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("apiCompatibility") {
            group = "verification"
            description = "Checks the public API against API_BASELINE_VERSION."
            notCompatibleWithConfigurationCache("The japicmp task owns a non-serializable tool classpath.")
            dependsOn(tasks.jar)
            oldClasspath.from(apiBaseline)
            newClasspath.from(tasks.jar)
            packageIncludes =
                listOf(
                    "com.iantapply.orchestra.api.*",
                    "com.iantapply.orchestra.domain.*",
                    "com.iantapply.orchestra.port.*",
                )
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
        dependsOn(apiCompatibility)
    }
}
