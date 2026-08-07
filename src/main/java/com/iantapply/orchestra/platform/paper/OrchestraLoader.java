package com.iantapply.orchestra.platform.paper;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/** Resolves runtime database libraries through Paper's plugin classpath loader. */
public final class OrchestraLoader implements PluginLoader {

    /** Creates the Paper classpath loader. */
    public OrchestraLoader() {}

    /** Adds HikariCP and the PostgreSQL driver to the Paper plugin classpath. */
    @Override
    public void classloader(final PluginClasspathBuilder builder) {
        Properties versions = libraryVersions();
        MavenLibraryResolver libraries = new MavenLibraryResolver();
        libraries.addRepository(
                new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
                        .build());
        libraries.addDependency(
                new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:" + versions.getProperty("hikari")), null));
        libraries.addDependency(new Dependency(
                new DefaultArtifact("org.postgresql:postgresql:" + versions.getProperty("postgresql")), null));
        builder.addLibrary(libraries);
    }

    private static Properties libraryVersions() {
        Properties versions = new Properties();
        try (var input = OrchestraLoader.class.getResourceAsStream("/orchestra-libraries.properties")) {
            if (input == null) throw new IllegalStateException("Missing runtime library version metadata");
            versions.load(input);
            return versions;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read runtime library versions", failure);
        }
    }
}
