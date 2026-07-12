package io.github.cyfko.veridot.tests;

import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

public class MariaDBIT extends DatabaseIT {
    public MariaDBIT() {
        super(new MariaDBContainer<>(DockerImageName.parse("mariadb:11")));
    }
}
