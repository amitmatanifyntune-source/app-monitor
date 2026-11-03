# App Monitor server image: UI + server + downloadable agent jar, built from this repository.
# Build context is the repository root:  docker build -f deploy/docker/Dockerfile -t app-monitor .

# 1) Web UI -> server/src/main/resources/static
FROM node:20-alpine AS ui
WORKDIR /src/ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui/ ./
RUN mkdir -p ../server/src/main/resources && npm run build

# 2) Server and agent jars
FROM maven:3.9-eclipse-temurin-21 AS jars
WORKDIR /src
COPY agent/pom.xml agent/pom.xml
COPY server/pom.xml server/pom.xml
RUN mvn -q -B -f agent/pom.xml dependency:go-offline && mvn -q -B -f server/pom.xml dependency:go-offline
COPY agent/src agent/src
COPY server/src server/src
COPY --from=ui /src/server/src/main/resources/static server/src/main/resources/static
RUN mvn -q -B -f agent/pom.xml package -DskipTests && mvn -q -B -f server/pom.xml package -DskipTests

# 3) Runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S monitor && adduser -S -G monitor -h /app monitor
WORKDIR /app
COPY --from=jars /src/server/target/app-monitor-server.jar /app/app-monitor-server.jar
COPY --from=jars /src/agent/target/fyntune-agent.jar /app/fyntune-agent.jar
USER monitor
ENV AGENT_JAR=/app/fyntune-agent.jar \
    PORT=8095 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8095
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO /dev/null http://127.0.0.1:8095/ || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app-monitor-server.jar"]
