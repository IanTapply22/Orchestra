plugins {
    id("java-library")
    id("maven-publish")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    compileOnly("org.postgresql:postgresql:42.7.12")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
            val repository = providers.environmentVariable("GITHUB_REPOSITORY")
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

tasks {
    test {
        useJUnitPlatform()
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }
}
