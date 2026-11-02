FROM gradle:8.14.6-jdk21@sha256:aa72246029828dd4b4ddb5d4aba31f72ee070cafe52dc85ad5cc3296007ace6f AS build

COPY security/jdk21-blocked.certs /tmp/jdk21-blocked.certs
RUN set -eu; \
    shipped="$JAVA_HOME/lib/security/blocked.certs"; \
    sed 1d "$shipped" | sort > /tmp/shipped.fp; \
    sed 1d /tmp/jdk21-blocked.certs | sort > /tmp/override.fp; \
    if [ -n "$(comm -23 /tmp/shipped.fp /tmp/override.fp)" ]; then \
      echo "this JDK blocks certificates security/jdk21-blocked.certs does not: remove the override"; \
      exit 1; \
    fi; \
    cp /tmp/jdk21-blocked.certs "$shipped"; \
    grep -qx 42C4ACCD12C4F59597925E1A08A7A90102608543809308D50EC427E118BA1493 "$shipped"; \
    rm /tmp/jdk21-blocked.certs /tmp/shipped.fp /tmp/override.fp

WORKDIR /src

COPY settings.gradle build.gradle ./
COPY modules/domain/build.gradle ./modules/domain/
COPY modules/application/build.gradle ./modules/application/
COPY modules/infrastructure/build.gradle ./modules/infrastructure/
COPY modules/api/build.gradle ./modules/api/

RUN gradle --no-daemon dependencies --quiet || true

COPY scripts ./scripts

RUN apt-get update \
  && apt-get install --no-install-recommends -y git \
  && sh scripts/sync-contracts \
  && rm -rf /var/lib/apt/lists/*
COPY modules ./modules

RUN gradle --no-daemon :modules:api:bootJar -x test \
    && cp modules/api/build/libs/*.jar /src/application.jar

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a AS final

RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

COPY security/jdk21-blocked.certs /tmp/jdk21-blocked.certs
RUN set -eu; \
    shipped="$JAVA_HOME/lib/security/blocked.certs"; \
    sed 1d "$shipped" | sort > /tmp/shipped.fp; \
    sed 1d /tmp/jdk21-blocked.certs | sort > /tmp/override.fp; \
    if [ -n "$(comm -23 /tmp/shipped.fp /tmp/override.fp)" ]; then \
      echo "this JDK blocks certificates security/jdk21-blocked.certs does not: remove the override"; \
      exit 1; \
    fi; \
    cp /tmp/jdk21-blocked.certs "$shipped"; \
    grep -qx 42C4ACCD12C4F59597925E1A08A7A90102608543809308D50EC427E118BA1493 "$shipped"; \
    rm /tmp/jdk21-blocked.certs /tmp/shipped.fp /tmp/override.fp

RUN groupadd --system --gid 10001 payment \
    && useradd --system --uid 10001 --gid 10001 --no-create-home payment

WORKDIR /app

COPY --from=build --chown=10001:10001 /src/application.jar /app/application.jar

USER 10001:10001

EXPOSE 8003 50056


HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8003/health/ready > /dev/null || exit 1

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
