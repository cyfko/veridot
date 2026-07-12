package io.github.cyfko.veridot.tests;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class PostgresIT extends DatabaseIT {
    public PostgresIT() {
        super(new PostgreSQLContainer<>(DockerImageName.parse("postgres:15")));
    }
}
