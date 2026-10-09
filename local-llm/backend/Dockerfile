FROM gradle:9.6.1-jdk21 AS build
WORKDIR /workspace
COPY backend/ .
RUN --mount=type=cache,id=local-llm-gradle,target=/home/gradle/.gradle gradle --no-daemon test bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/local-llm-backend.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 CMD curl --fail --silent --max-time 2 http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
