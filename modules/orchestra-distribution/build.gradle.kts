import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    `maven-publish`
    id("xyz.jpenilla.run-paper")
}

val bundledProjects =
    listOf(
        project(":orchestra-api"),
        project(":orchestra-core"),
        project(":orchestra-adapter-memory"),
        project(":orchestra-adapter-postgres"),
        project(":orchestra-adapter-redis"),
        project(":orchestra-platform-paper"),
        project(":orchestra-platform-velocity"),
    )

dependencies {
    bundledProjects.forEach { implementation(project(it.path)) }
}

val integrationTest = sourceSets.create("integrationTest")
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, sourceSets.main.get().output)
    bundledProjects.forEach { add(integrationTest.implementationConfigurationName, project(it.path)) }
    add(integrationTest.implementationConfigurationName, libs.hikari)
    add(integrationTest.implementationConfigurationName, libs.postgresql)
    add(integrationTest.implementationConfigurationName, libs.testcontainers)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.junit)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.postgresql)
}

tasks.named<Jar>("jar") {
    archiveBaseName = "Orchestra"
    destinationDirectory = rootProject.layout.buildDirectory.dir("libs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(bundledProjects.map { it.tasks.named("classes") })
    bundledProjects.forEach {
        from(
            it.extensions
                .getByType<SourceSetContainer>()
                .named("main")
                .get()
                .output,
        )
    }
}

val integrationTestTask =
    tasks.register<Test>("integrationTest") {
        description = "Runs PostgreSQL and Redis integration tests when Docker is available."
        group = "verification"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(tasks.test)
    }

tasks.check {
    dependsOn(integrationTestTask)
}

tasks.runServer {
    minecraftVersion("26.2")
    jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
}

publishing {
    publications {
        create<MavenPublication>("plugin") {
            artifact(tasks.jar)
            artifactId = "orchestra"
            pom {
                name = "Orchestra"
                description = project.description.toString()
                url = "https://github.com/IanTapply22/Orchestra"
                scm {
                    connection = "scm:git:https://github.com/IanTapply22/Orchestra.git"
                    developerConnection = "scm:git:ssh://git@github.com/IanTapply22/Orchestra.git"
                    url = "https://github.com/IanTapply22/Orchestra"
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            val repository =
                providers
                    .environmentVariable("GITHUB_REPOSITORY")
                    .orElse("IanTapply22/Orchestra")
                    .get()
                    .lowercase()
            url = uri("https://maven.pkg.github.com/$repository")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
