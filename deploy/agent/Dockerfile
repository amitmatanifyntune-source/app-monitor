# Fyntune agent image: Java 21 runtime + fyntune-agent.jar, so the app server needs no Java of its own.
# Put fyntune-agent.jar (Help: install & track -> Download agent) next to this file, then:
#   docker compose build      (or: docker build -t fyntune-agent .)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/fyntune-agent
COPY fyntune-agent.jar /opt/fyntune-agent/fyntune-agent.jar
# -XX:-UseContainerSupport: report the HOST's CPU and memory, not the container's limits
ENTRYPOINT ["java", "-XX:-UseContainerSupport", "-Xmx128m", "-jar", "/opt/fyntune-agent/fyntune-agent.jar", "/etc/fyntune-agent/agent.json"]
