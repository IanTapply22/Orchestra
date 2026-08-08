plugins {
    `java-library`
}

dependencies {
    implementation(project(":orchestra-api"))
    implementation(project(":orchestra-core"))
    implementation(project(":orchestra-adapter-redis"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
}

val generatedSources = layout.buildDirectory.dir("generated/sources/orchestra")
val generateBuildInfo =
    tasks.register<Copy>("generateBuildInfo") {
        from("src/main/templates")
        into(generatedSources)
        expand("version" to project.version.toString())
    }

sourceSets.main {
    java.srcDir(generatedSources)
}

tasks.compileJava {
    dependsOn(generateBuildInfo)
}
