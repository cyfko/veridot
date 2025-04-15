package io.github.cyfko.veridot.tests;

import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

public class MariaDBTest extends DatabaseTest {
    public MariaDBTest() {
        super(new MariaDBContainer<>(DockerImageName.parse("mariadb:11")));
    }
}
