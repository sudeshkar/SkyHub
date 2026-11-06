# The disruption agent's image (ADR-0082). Build from the agent's own folder:
#   docker build -f docker/disruption-agent.Dockerfile -t skyhub/disruption-agent services/disruption-agent

# ---- Build: install the agent and its libraries into a virtual environment. ----
FROM python:3.14-slim AS build
WORKDIR /build
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
# The project file is copied before the code, so a code change does not install every library again.
COPY pyproject.toml ./
COPY src src
RUN pip install --no-cache-dir .

# ---- Run: Python and the finished environment, with no pip cache and no sources. ----
FROM python:3.14-slim
# Not root: a flaw in the agent must not give the attacker the container.
RUN groupadd --system --gid 10001 skyhub && useradd --system --uid 10001 --gid skyhub --no-create-home skyhub
COPY --from=build /opt/venv /opt/venv
USER skyhub
# Inside a container the agent must listen on every address, or nothing outside can reach it (its default is 127.0.0.1).
# Whether the port is published is for the compose file or the cluster to decide. These are addresses, not secrets.
ENV PATH="/opt/venv/bin:$PATH" PYTHONUNBUFFERED=1 SKYHUB_AGENT_HOST=0.0.0.0 SKYHUB_AGENT_PORT=8084
# SKYHUB_AGENT_API_TOKEN, SKYHUB_AGENT_USERNAME, SKYHUB_AGENT_PASSWORD and GROQ_API_KEY come in when the container starts.
ENTRYPOINT ["python", "-m", "disruption_agent.web"]
