# The website's image (ADR-0082). Build from the website's own folder:
#   docker build -f docker/website.Dockerfile -t skyhub/website frontend

# ---- Build: install the libraries and build the site. ----
FROM node:24-slim AS build
WORKDIR /build
# The library list changes far less often than the code, so it is its own layer.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---- Run: Node and the traced files the site needs (output: "standalone"), nothing else. ----
FROM node:24-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
# The node image already has an unprivileged user ("node", id 1000); everything is given to it, not to root.
COPY --from=build --chown=node:node /build/.next/standalone ./
COPY --from=build --chown=node:node /build/.next/static ./.next/static
COPY --from=build --chown=node:node /build/public ./public
USER node
# SKYHUB_SEARCH_API_URL, SKYHUB_BOOKING_API_URL, SKYHUB_AGENT_API_URL, SKYHUB_AGENT_API_TOKEN and REDIS_URL come in
# when the container starts: the addresses are read at run time, so one image works in every environment.
CMD ["node", "server.js"]
