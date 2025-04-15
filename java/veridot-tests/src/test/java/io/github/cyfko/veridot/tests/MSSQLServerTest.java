package io.github.cyfko.veridot.tests;

import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

public class MSSQLServerTest extends DatabaseTest {
    public MSSQLServerTest() {
        super(new MSSQLServerContainer<>(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                .acceptLicense()
        );
    }
}
