package io.github.cyfko.veridot.tests;


import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public class MySQLIT extends DatabaseIT {
    public MySQLIT() {
        super(new MySQLContainer<>(DockerImageName.parse("mysql:8.4")));
    }
}
