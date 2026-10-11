FROM gradle:9.6.1-jdk21 AS build
WORKDIR /workspace
COPY backend/ .
RUN --mount=type=cache,id=local-llm-gradle,target=/home/gradle/.gradle gradle --no-daemon test bootJar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/build/libs/local-llm-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
