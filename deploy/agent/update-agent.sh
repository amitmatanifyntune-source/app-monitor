#!/usr/bin/env bash
# Upgrades the App Monitor agent on this server to the monitor's current version, in place: no reinstall, no password.
# The new agent is fetched from the monitor with this agent's own id and key (checksum verified), or taken from a
# fyntune-agent.jar next to this script. Docker installs also get the kit's newer Dockerfile and compose file; systemd
# installs get the lean JVM settings and CPU/memory caps. Nothing else changes (no OpenTelemetry, PHP or Composer).
#   sudo ./update-agent.sh                     # agent in /opt/fyntune-agent
#   sudo ./update-agent.sh --agent-dir DIR     # agent elsewhere
exec "$(cd "$(dirname "$0")" && pwd)/enable-otel.sh" --upgrade-only "$@"
