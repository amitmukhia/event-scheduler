# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre-alpine as runtime

# Set workdir
WORKDIR /app

# Copy jar from build context (expect multi-stage build or mounted target)
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

# Expose app port
EXPOSE 8080

# Environment variables (override at runtime)
ENV JAVA_OPTS=""

# Healthcheck (optional)
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

