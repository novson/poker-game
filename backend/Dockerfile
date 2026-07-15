FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B verify

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S poker && adduser -S poker -G poker
WORKDIR /app
COPY --from=build /workspace/target/poker-backend-1.0.0.jar app.jar
USER poker
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

