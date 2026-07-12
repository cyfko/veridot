package io.github.cyfko.veridot.tests;

import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

public class MSSQLServerIT extends DatabaseIT {
    private static MSSQLServerContainer<?> createContainer() {
        MSSQLServerContainer<?> container = new MSSQLServerContainer<>(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"));
        container.acceptLicense();
        return container;
    }

    public MSSQLServerIT() {
        super(createContainer());
    }
}
