package com.breynisson.router;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

import java.util.ArrayList;
import java.util.List;

// DataSourceAutoConfiguration is excluded because DatabaseAdapter manages its own HikariCP
// DataSource directly (see DatabaseAdapter.configure()/rebuildDataSource()) rather than exposing
// a Spring-managed javax.sql.DataSource bean. Without this exclusion, spring-boot-starter-jdbc
// (pulled in for HikariCP) makes Spring Boot try to auto-configure its own DataSource bean from
// spring.datasource.* properties, which don't exist (this app uses postgres.* instead) -- that
// failure only surfaces when a full ApplicationContext is built, e.g. in SpringBootApplicationTest.
@org.springframework.boot.autoconfigure.SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class SpringBootApplication {

    /**
     * A main method to start this application.
     */
    public static void main(String[] args) {
        org.springframework.boot.builder.SpringApplicationBuilder builder =
                new org.springframework.boot.builder.SpringApplicationBuilder(SpringBootApplication.class);
        boolean migrating = java.util.Arrays.stream(args)
                .anyMatch(a -> a.startsWith("--digitalme.migrate-sqlite-path"));
        if (migrating) {
            builder.web(org.springframework.boot.WebApplicationType.NONE);
            // Prevent the Camel file-watch/scheduler routes (FileChangeWatcher, ContentReceive)
            // from loading during a migration run -- left enabled, they index newly-noticed local
            // files into MCP_EMBEDDING/TEXT_ENTRY concurrently with the migrator's own bulk copy,
            // racing it for the same primary keys. Camel's route collector requires the directory
            // itself to exist (even if nothing in it matches a wildcard), so point the pattern at a
            // freshly-created, guaranteed-empty temp directory rather than an arbitrary nonexistent
            // path. This must be passed as a command-line argument, not via builder.properties()
            // (which sets low-priority default properties that application.properties's own
            // explicit setting for this key overrides).
            List<String> argsWithOverride = new ArrayList<>(java.util.Arrays.asList(args));
            try {
                java.nio.file.Path noRoutesDir = java.nio.file.Files.createTempDirectory("digitalme-no-routes-");
                argsWithOverride.add("--camel.springboot.routes-include-pattern=file:"
                        + noRoutesDir.toString().replace('\\', '/') + "/*.xml");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            args = argsWithOverride.toArray(new String[0]);
        }
        builder.run(args);
    }

}
