FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system notifyflow \
    && useradd --system --gid notifyflow --no-create-home notifyflow

COPY --chown=notifyflow:notifyflow target/notifyflow-0.0.1-SNAPSHOT.jar app.jar

USER notifyflow

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]