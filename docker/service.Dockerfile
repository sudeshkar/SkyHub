# One image recipe for the three Java services (ADR-0081).
#   docker build -f docker/service.Dockerfile --build-arg SERVICE=booking -t skyhub/booking .
# SERVICE is flight-search, booking or notification. Build from the repository root: the parent pom and api-specs/ are needed.

# ---- Build: the JDK, Maven and the sources. None of this is in the final image. ----
FROM eclipse-temurin:25-jdk AS build
ARG SERVICE
WORKDIR /build

# The dependency list changes far less often than the code, so it is its own layer: a code change does not download the world again.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY services/flight-search/pom.xml services/flight-search/
COPY services/booking/pom.xml services/booking/
COPY services/notification/pom.xml services/notification/
COPY api-specs api-specs
# go-offline only warms the cache: it cannot resolve every build plugin, so a miss here is fine (the package step below is the one that counts).
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q -pl services/${SERVICE} -am dependency:go-offline || true

COPY services/${SERVICE}/src services/${SERVICE}/src
# Tests run in CI before an image is built, so they are neither compiled nor run a second time here.
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q -pl services/${SERVICE} -am package -Dmaven.test.skip=true \
 && cp services/${SERVICE}/target/*-SNAPSHOT.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Run: a Java runtime and the service's layers, nothing else. ----
FROM eclipse-temurin:25-jre
# Not root: a flaw in the service must not give the attacker the container. A fixed id so mounted volumes can be given to it.
RUN groupadd --system --gid 10001 skyhub && useradd --system --uid 10001 --gid skyhub --no-create-home skyhub
WORKDIR /app
# Rarely changing layers first, the service's own code last, so a rebuild pushes and pulls only the small top layer.
COPY --from=build --chown=skyhub:skyhub /build/extracted/dependencies/ ./
COPY --from=build --chown=skyhub:skyhub /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=skyhub:skyhub /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=skyhub:skyhub /build/extracted/application/ ./
USER skyhub
# Size the heap from the container's memory limit, and stop (so the orchestrator restarts it) rather than limp on after running out.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Keys, passwords and addresses are never written here: they come in as environment variables when the container starts.
ENTRYPOINT ["java", "-jar", "application.jar"]
