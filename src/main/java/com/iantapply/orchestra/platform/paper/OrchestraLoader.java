package com.iantapply.orchestra.platform.paper;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/** Resolves runtime database libraries through Paper's plugin classpath loader. */
public final class OrchestraLoader implements PluginLoader {

    /** Creates the Paper classpath loader. */
    public OrchestraLoader() {
    }

    /** Adds HikariCP and the PostgreSQL driver to the Paper plugin classpath. */
    @Override
    public void classloader(final PluginClasspathBuilder builder) {
        MavenLibraryResolver libraries = new MavenLibraryResolver();
        libraries.addRepository(new RemoteRepository.Builder(
                "central", "default", "https://repo.maven.apache.org/maven2/").build());
        libraries.addDependency(new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:7.0.2"), null));
        libraries.addDependency(new Dependency(new DefaultArtifact("org.postgresql:postgresql:42.7.12"), null));
        builder.addLibrary(libraries);
    }
}
