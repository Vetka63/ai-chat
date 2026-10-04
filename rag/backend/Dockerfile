FROM gradle:9.6.1-jdk21 AS build
WORKDIR /workspace
COPY backend/ .
COPY api/ /api/
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/rag-backend.jar app.jar
COPY corpus/progit-ru corpus/progit-ru
ENV RAG_CORPUS_PATH=/app/corpus/progit-ru RAG_DATABASE_PATH=/app/data/rag.sqlite
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
