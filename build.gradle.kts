plugins {
    id("java-library")
    jacoco
    id("maven-publish")
    id("com.diffplug.spotless") version "8.8.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.get())
    compileOnly(libs.hikari)
    compileOnly(libs.postgresql)
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.launcher)
}

dependencyLocking {
    lockAllConfigurations()
}

paperPluginYaml {
    main = "com.iantapply.orchestra.platform.paper.OrchestraPlugin"
    loader = "com.iantapply.orchestra.platform.paper.OrchestraLoader"
    apiVersion = "26.2"

    authors.addAll("Gucci Fox")
    prefix = "Orchestra"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

val generatedSources = layout.buildDirectory.dir("generated/sources/orchestra")
val generatedResources = layout.buildDirectory.dir("generated/resources/orchestra")
val hikariVersion = libs.versions.hikari.get()
val postgresqlVersion = libs.versions.postgresql.get()
val generateBuildInfo =
    tasks.register<Copy>("generateBuildInfo") {
        from("src/main/templates")
        into(generatedSources)
        expand("version" to project.version.toString())
    }
val generateRuntimeLibraryVersions =
    tasks.register<Copy>("generateRuntimeLibraryVersions") {
        from("src/main/resourceTemplates")
        into(generatedResources)
        expand(
            "hikariVersion" to hikariVersion,
            "postgresqlVersion" to postgresqlVersion,
        )
    }

sourceSets.main {
    java.srcDir(generatedSources)
    resources.srcDir(generatedResources)
}

val integrationTest = sourceSets.create("integrationTest")
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, sourceSets.main.get().output)
    add(integrationTest.implementationConfigurationName, libs.hikari)
    add(integrationTest.implementationConfigurationName, libs.postgresql)
    add(integrationTest.implementationConfigurationName, libs.testcontainers)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.junit)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.postgresql)
}

publishing {
    publications {
        create<MavenPublication>("plugin") {
            from(components["java"])
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

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat()
        formatAnnotations()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("projectFiles") {
        target(
            "*.md",
            "*.properties",
            "gradle/*.toml",
            ".gitattributes",
            ".gitignore",
            ".githooks/*",
            ".github/**/*.yml",
            ".github/**/*.yaml",
            "src/**/*.yml",
            "src/**/*.yaml",
            "src/**/*.sql",
            "src/**/*.properties",
        )
        targetExclude("src/main/resourceTemplates/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks {
    compileJava {
        dependsOn(generateBuildInfo)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    processResources {
        dependsOn(generateRuntimeLibraryVersions)
    }

    test {
        useJUnitPlatform()
        finalizedBy(jacocoTestReport)
    }

    val integrationTestTask =
        register<Test>("integrationTest") {
            description = "Runs PostgreSQL and Redis integration tests when Docker is available."
            group = "verification"
            testClassesDirs = integrationTest.output.classesDirs
            classpath = integrationTest.runtimeClasspath
            useJUnitPlatform()
            shouldRunAfter(test)
        }

    jacocoTestReport {
        dependsOn(test)
        reports {
            html.required = true
            xml.required = true
        }
    }

    jacocoTestCoverageVerification {
        dependsOn(test)
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

    check {
        dependsOn(jacocoTestCoverageVerification, integrationTestTask)
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    register("lint") {
        group = "verification"
        description = "Checks source code and project file formatting."
        dependsOn(spotlessCheck)
    }

    register("lintFix") {
        group = "formatting"
        description = "Formats source code and project files, then verifies the result."
        dependsOn(spotlessApply)
        finalizedBy(spotlessCheck)
    }

    register<Exec>("installGitHooks") {
        group = "build setup"
        description = "Configures this Git checkout to use the tracked hooks in .githooks."
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            commandLine("git", "config", "core.hooksPath", ".githooks")
        } else {
            commandLine(
                "sh",
                "-c",
                "chmod +x .githooks/pre-commit && git config core.hooksPath .githooks",
            )
        }
    }
}
