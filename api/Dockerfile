# The jar is built on the host (mvn -DskipTests package) or in CI; this image only runs it.
FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY target/inbound-atp-api-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-jar", "app.jar"]
