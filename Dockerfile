FROM gradle:8.14.6-jdk21@sha256:c71fc6625c18e889654adeb86c99974028bc888505f6e1fd7eccad057174877d AS build

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

# `bin/` is the compiled-class output directory here and is excluded from the build context,
# so the script lives in scripts/.
COPY scripts ./scripts

# The wire contracts, at the commit bin/sync-contracts pins. Fetched rather than copied: this
# repository tracks no .proto at all, which is what S9's exit criterion measures.
RUN apt-get update \
  && apt-get install --no-install-recommends -y git \
  && sh scripts/sync-contracts \
  && rm -rf /var/lib/apt/lists/*
COPY modules ./modules

RUN gradle --no-daemon :modules:api:bootJar -x test \
    && cp modules/api/build/libs/*.jar /src/application.jar

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99 AS final

# The base is pinned by digest, which also means it stops receiving Ubuntu's security updates. CI's image
# scan fails the build on a CRITICAL that has a fix available — eight of them on the first run — so the
# patches are applied here. The digest still decides which JRE ships.
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

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/application.jar"]
