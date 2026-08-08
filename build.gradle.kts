import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.jvm.tasks.Jar

plugins {
    base
    jacoco
    id("com.diffplug.spotless") version "8.8.0"
    id("com.github.spotbugs") version "6.5.9" apply false
    id("xyz.jpenilla.run-paper") version "3.0.2" apply false
}

allprojects {
    group = providers.gradleProperty("group").get()
    version = providers.gradleProperty("version").get()
    description = providers.gradleProperty("description").get()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "com.github.spotbugs")

    extensions.configure<SpotBugsExtension> {
        excludeFilter = rootProject.file("config/spotbugs-exclude.xml")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
        withSourcesJar()
        withJavadocJar()
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.awaitility)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<SpotBugsTask>().configureEach {
        reports.create("html") { required = true }
    }
    tasks.named("spotbugsTest").configure { enabled = false }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            html.required = true
            xml.required = true
        }
    }
}

spotless {
    java {
        target("modules/**/src/**/*.java")
        targetExclude("**/build/**")
        palantirJavaFormat()
        formatAnnotations()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("*.gradle.kts", "modules/**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("projectFiles") {
        target(
            "*.md",
            "docs/**/*.md",
            "config/**/*.xml",
            "*.properties",
            "gradle/*.toml",
            ".gitattributes",
            ".gitignore",
            ".githooks/*",
            ".github/**/*.yml",
            ".github/**/*.yaml",
            "modules/**/*.yml",
            "modules/**/*.yaml",
            "modules/**/*.sql",
            "modules/**/*.properties",
        )
        targetExclude("modules/**/resourceTemplates/**")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val moduleProjects = subprojects.filter { it.name != "orchestra-distribution" }

val aggregateJavadoc =
    tasks.register<Sync>("javadoc") {
        dependsOn(moduleProjects.map { it.tasks.named("javadoc") })
        into(layout.buildDirectory.dir("docs/javadoc"))
        from("docs/javadoc-index.html") { rename { "index.html" } }
        moduleProjects.forEach { module ->
            from(module.layout.buildDirectory.dir("docs/javadoc")) { into(module.name) }
        }
    }

val aggregateCoverageReport =
    tasks.register<JacocoReport>("jacocoTestReport") {
        dependsOn(moduleProjects.map { it.tasks.named("test") })
        executionData.from(moduleProjects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
        sourceDirectories.from(
            moduleProjects.map {
                it.extensions
                    .getByType<SourceSetContainer>()
                    .named("main")
                    .get()
                    .allSource.srcDirs
            },
        )
        classDirectories.from(
            moduleProjects.map {
                it.extensions
                    .getByType<SourceSetContainer>()
                    .named("main")
                    .get()
                    .output
            },
        )
        reports {
            html.required = true
            xml.required = true
        }
    }

val aggregateCoverageVerification =
    tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(aggregateCoverageReport)
        executionData.from(aggregateCoverageReport.map { it.executionData })
        sourceDirectories.from(aggregateCoverageReport.map { it.sourceDirectories })
        classDirectories.from(aggregateCoverageReport.map { it.classDirectories })
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.73".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    minimum = "0.67".toBigDecimal()
                }
            }
        }
    }

tasks.register("lint") {
    group = "verification"
    description = "Checks source code and project file formatting."
    dependsOn(tasks.named("spotlessCheck"))
}

tasks.register("lintFix") {
    group = "formatting"
    description = "Formats source code and project files, then verifies the result."
    dependsOn(tasks.named("spotlessApply"))
    finalizedBy(tasks.named("spotlessCheck"))
}

tasks.named("check") {
    dependsOn(subprojects.map { it.tasks.named("check") }, aggregateCoverageVerification)
}

tasks.register("jar") {
    group = "build"
    dependsOn(":orchestra-distribution:jar")
}

tasks.register("runServer") {
    group = "run paper"
    dependsOn(":orchestra-distribution:runServer")
}

tasks.register<Exec>("installGitHooks") {
    group = "build setup"
    description = "Configures this Git checkout to use the tracked hooks in .githooks."
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        commandLine("git", "config", "core.hooksPath", ".githooks")
    } else {
        commandLine("sh", "-c", "chmod +x .githooks/pre-commit && git config core.hooksPath .githooks")
    }
}
