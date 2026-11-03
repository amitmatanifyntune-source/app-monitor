# Fyntune agent image: Java 21 runtime + fyntune-agent.jar, so the app server needs no Java of its own.
# Put fyntune-agent.jar (Help: install & track -> Download agent) next to this file, then:
#   docker compose build      (or: docker build -t fyntune-agent .)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/fyntune-agent
COPY fyntune-agent.jar /opt/fyntune-agent/fyntune-agent.jar
# -XX:-UseContainerSupport: report the HOST's CPU and memory, not the container's limits.
# Serial GC, C1 only and small metaspace/code cache: measured at about half the CPU and a third less memory than defaults.
ENTRYPOINT ["java", "-XX:-UseContainerSupport", "-Xms16m", "-Xmx128m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:MaxMetaspaceSize=64m", "-XX:ReservedCodeCacheSize=32m", "-Xss512k", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/fyntune-agent/fyntune-agent.jar", "/etc/fyntune-agent/agent.json"]
