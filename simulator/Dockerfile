# The jar is built on the host or in CI; this image only runs it.
FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 sim
WORKDIR /app
COPY target/inbound-atp-simulator-*.jar app.jar
USER sim
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
