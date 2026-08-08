dependencies {
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    implementation(project(":orchestra-api"))
    implementation(project(":orchestra-core"))
    implementation(project(":orchestra-adapter-memory"))
    implementation(project(":orchestra-adapter-postgres"))
    implementation(project(":orchestra-adapter-redis"))
    compileOnly(libs.hikari)
    compileOnly(libs.postgresql)
}

val eventValidatorRuntime = configurations.create("eventValidatorRuntime")
dependencies {
    eventValidatorRuntime(libs.paper.api)
}

tasks.register<JavaExec>("validateEvents") {
    group = "verification"
    description = "Validates schema-versioned Orchestra event YAML files."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath + eventValidatorRuntime
    mainClass = "com.iantapply.orchestra.platform.paper.ValidateEventsMain"
    val eventDirectory =
        providers
            .gradleProperty("orchestra.eventsDir")
            .orElse(
                layout.projectDirectory
                    .dir("src/main/resources/events")
                    .asFile.absolutePath,
            )
    argumentProviders.add(CommandLineArgumentProvider { listOf(eventDirectory.get()) })
}

val generatedResources = layout.buildDirectory.dir("generated/resources/orchestra")
val generateRuntimeLibraryVersions =
    tasks.register<Copy>("generateRuntimeLibraryVersions") {
        from("src/main/resourceTemplates")
        into(generatedResources)
        expand(
            "hikariVersion" to libs.versions.hikari.get(),
            "postgresqlVersion" to libs.versions.postgresql.get(),
        )
    }

sourceSets.main {
    resources.srcDir(generatedResources)
}

tasks.processResources {
    dependsOn(generateRuntimeLibraryVersions)
}
