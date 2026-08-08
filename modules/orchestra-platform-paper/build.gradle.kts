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
