# S7-07 — the backend container image (M-1).
#
# Built from the jar produced by `./mvnw package`, not by running Maven inside the build. The
# jar that ships is therefore the exact artifact the gate ran against — `./mvnw verify
# -Pdb-integration-test`, the real coverage gate per CLAUDE.md — rather than a second build
# of the same source that nothing tested. The build context is backend/, so the COPY below
# reaches target/ directly.
#
# WHAT IS DELIBERATELY NOT IN THIS IMAGE: any credential, any endpoint, any tenant identifier.
# Uqudo's client id and secret come from Secrets Manager and its endpoints from Parameter
# Store, both injected by the task definition, because the tenant changes before production
# and a baked value would make that a rebuild (R-007, and CLAUDE.md's hard rule). The same
# image runs as staging and as production; only the parameter path differs.

# ---------------------------------------------------------------------------- trust store
# Amazon's RDS trust store, fetched at build time rather than vendored into the repository.
# GLOBAL rather than the eu-central-1 bundle: it covers every region, so relocating or
# rebuilding the database is not also a certificate change. application-aws.properties points
# sslrootcert at the path this stage writes and connects with sslmode=verify-full, which fails
# closed if the file is absent -- deliberately, and now a property of the image rather than of
# the environment.
FROM alpine:3.21 AS truststore
RUN apk add --no-cache curl ca-certificates \
 && curl -fsSL --retry 3 --retry-delay 2 \
      -o /global-bundle.pem \
      https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
 && test -s /global-bundle.pem \
 && grep -q "BEGIN CERTIFICATE" /global-bundle.pem

# ---------------------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-alpine

# A container that runs as root is a container whose every later mistake is a root mistake.
# The application needs no write access anywhere outside /tmp.
RUN addgroup -S fru && adduser -S -G fru -h /app fru

WORKDIR /app
COPY --from=truststore /global-bundle.pem /opt/rds/global-bundle.pem
COPY --chown=fru:fru target/backend-0.0.1-SNAPSHOT.jar /app/backend.jar

USER fru
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the task definition owns the memory limit, and a
# hardcoded heap here would silently disagree with it the moment that limit is tuned.
# UseContainerSupport is on by default on 21 and is stated because it is what makes the
# percentage read the cgroup limit rather than the host's memory.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# SPRING_PROFILES_ACTIVE is set here rather than left to the task definition so that running
# this image anywhere -- including by hand, to reproduce a production problem -- cannot
# silently fall back to the development defaults in application.properties.
ENV SPRING_PROFILES_ACTIVE=aws

ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
