rootProject.name = "Orchestra"

include(
    "orchestra-api",
    "orchestra-core",
    "orchestra-adapter-memory",
    "orchestra-adapter-postgres",
    "orchestra-adapter-redis",
    "orchestra-platform-paper",
    "orchestra-platform-velocity",
    "orchestra-distribution",
)

rootProject.children.forEach { project ->
    project.projectDir = file("modules/${project.name}")
}
