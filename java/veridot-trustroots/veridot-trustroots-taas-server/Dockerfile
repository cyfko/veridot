FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Default HTTP API port (configurable via SERVER_PORT)
ENV SERVER_PORT=8080

# The jar is expected to be built via 'mvn clean package'
COPY target/veridot-trustroots-taas-server-*.jar app.jar

EXPOSE ${SERVER_PORT} 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
