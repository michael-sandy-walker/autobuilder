package com.walpro.autobuilder.home;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

/**
 * T4-ED: the home context -- what the app is before a project is bound.
 *
 * <p>It serves the home screen and its endpoints and owns the {@link ProjectLauncher}. It has no loop, no
 * graph, no database and no project: only the web stack is configured, and nothing is scanned. The run
 * context ({@code AutobuilderApplication}) is started beside it on a chosen project.
 *
 * <p>{@code @Profile("home")} keeps this class and its controller out of the run context, whose component
 * scan covers this package.
 */
@Slf4j
@Profile("home")
@SpringBootConfiguration
@ImportAutoConfiguration({
        PropertyPlaceholderAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        ServletWebServerFactoryAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class
})
@Import(HomeController.class)
public class HomeApplication {

    @Bean
    ProjectsDirectory projectsDirectory(
            // The directory whose subdirectories the home screen offers as projects, and where a new one is created.
            @Value("${autobuilder.home.projects-dir:data}") String dir) {
        return new ProjectsDirectory(Path.of(dir));
    }

    /** Where the home screen looks for projects. */
    public record ProjectsDirectory(Path path) {}

    @Bean
    Trash trash(ProjectsDirectory projects,
                // T4-EV: where a deleted project's directory is moved; emptying the trash erases what is in it.
                @Value("${autobuilder.home.trash-dir:${user.home}/.autobuilder/deleted}") String dir) {
        return new Trash(projects.path(), Path.of(dir));
    }

    /**
     * T4-EV: erases a lineage's nodes from the shared graph when the trash is emptied. The home context keeps no graph
     * connection (above): this opens one for the erasing and closes it, in batches so a long history is not one
     * transaction.
     *
     * <p>Never the {@code SystemState} node: it is ONE node for every project, the loop's root and subgoal pointer,
     * stamped with whichever run saved last (Neo4jGoalGraphService.setState). Erased with the project that saved last,
     * the user's count came out one node short of where it began (2026-11-16); its readers already ignore a pointer into
     * another lineage, so leaving it is harmless.
     */
    @Bean
    Trash.GraphEraser graphEraser(@Value("${spring.data.neo4j.uri:bolt://localhost:7687}") String uri,
                                  @Value("${spring.data.neo4j.username:neo4j}") String user,
                                  @Value("${spring.data.neo4j.password:}") String password) {
        return lineageId -> {
            try (org.neo4j.driver.Driver driver = org.neo4j.driver.GraphDatabase.driver(uri, org.neo4j.driver.AuthTokens.basic(user, password));
                 org.neo4j.driver.Session session = driver.session()) {
                long total = 0;
                while (true) {
                    long n = session.executeWrite(tx -> tx.run(
                            "MATCH (n) WHERE n.lineageId = $id AND NOT n:SystemState WITH n LIMIT 5000 DETACH DELETE n RETURN count(*) AS c",
                            java.util.Map.of("id", lineageId)).single().get("c").asLong());
                    total += n;
                    if (n == 0) return total;
                }
            }
        };
    }

    /**
     * Boots the home context, then -- when a project was named at startup -- starts the run on it through the
     * same launcher the home screen's "Start" uses. A fast-forwarded run that fails to start ends the JVM, as
     * a failed boot always has: in a scripted run nobody is looking at the page.
     */
    public static void launch(String[] args) {
        ProjectChoice fastForward = ProjectChoice.fromStartup(args, System.getProperties(), System.getenv());
        System.setProperty(ProjectLauncher.LOG_ROOT, homeDirectory().toString());
        ProjectLauncher launcher = new ProjectLauncher(args);
        try {
            // The startup arguments go to the launcher, not to this context: they describe the run.
            // main(): named for this class, so "Started AutobuilderApplication" keeps meaning the run context.
            ConfigurableApplicationContext home = new SpringApplicationBuilder(HomeApplication.class)
                    .main(HomeApplication.class).profiles("home")
                    .initializers(ctx -> ctx.getBeanFactory().registerSingleton("projectLauncher", launcher))
                    .run();
            // Back home, the log root returns here and logging is configured again from this context.
            launcher.onReturnHome(() -> {
                System.setProperty(ProjectLauncher.LOG_ROOT, homeDirectory().toString());
                LoggingSystem logging = LoggingSystem.get(HomeApplication.class.getClassLoader());
                logging.cleanUp();
                logging.initialize(new LoggingInitializationContext(home.getEnvironment()), null, null);
            });
        } catch (RuntimeException e) {
            // A busy home port must not stop a scripted run: it is started through the same launcher.
            if (fastForward == null) throw e;
            log.warn("T4-ED: the home screen did not start ({}); starting the run without it", e.toString());
        }
        if (fastForward == null) {
            log.info("T4-ED: no project named at startup -- the home screen is waiting");
            return;
        }
        try {
            launcher.start(fastForward).join();
        } catch (RuntimeException e) {
            System.exit(1);
        }
    }

    /** Logs of the home context, while no project is bound. */
    static Path homeDirectory() {
        return Path.of(System.getProperty("user.home"), ".autobuilder", "home");
    }
}
