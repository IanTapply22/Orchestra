import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension

plugins {
    base
    jacoco
    id("com.diffplug.spotless") version "8.9.0"
    id("com.github.spotbugs") version "6.5.10" apply false
    id("org.cyclonedx.bom") version "3.4.0"
    id("xyz.jpenilla.run-paper") version "3.0.2" apply false
}

allprojects {
    group = providers.gradleProperty("group").get()
    version =
        providers
            .environmentVariable("ORCHESTRA_VERSION")
            .orElse(providers.gradleProperty("version"))
            .map { it.removePrefix("v") }
            .get()
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

    pluginManager.withPlugin("maven-publish") {
        apply(plugin = "signing")
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    licenses {
                        license {
                            name = "GNU Affero General Public License v3.0 or later"
                            url = "https://www.gnu.org/licenses/agpl-3.0.html"
                            distribution = "repo"
                        }
                    }
                    scm {
                        connection = "scm:git:https://github.com/IanTapply22/Orchestra.git"
                        developerConnection = "scm:git:ssh://git@github.com/IanTapply22/Orchestra.git"
                        url = "https://github.com/IanTapply22/Orchestra"
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
        extensions.configure<SigningExtension> {
            val signingKey = providers.environmentVariable("SIGNING_KEY")
            val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
            setRequired(signingKey.isPresent)
            if (signingKey.isPresent) {
                useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
            }
            sign(project.extensions.getByType<PublishingExtension>().publications)
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
val aggregateCoverageExclusions =
    listOf(
        "**/platform/paper/OrchestraLoader*.class",
        "**/platform/paper/OrchestraPlugin*.class",
        "**/platform/velocity/OrchestraVelocityPlugin*.class",
    )

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
            moduleProjects.map { module ->
                module.extensions
                    .getByType<SourceSetContainer>()
                    .named("main")
                    .get()
                    .output
                    .asFileTree
                    .matching { exclude(aggregateCoverageExclusions) }
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

tasks.register("validateEvents") {
    group = "verification"
    description = "Validates event YAML files; override the directory with -Porchestra.eventsDir=<path>."
    dependsOn(":orchestra-platform-paper:validateEvents")
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
