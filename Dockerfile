FROM gradle:8.12-jdk21@sha256:4c01ef2d5b57f88578b20891d8640dc799855731b977850940057c1ea37ba8a6 AS build

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

FROM eclipse-temurin:21-jre@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037 AS final

RUN groupadd --system --gid 10001 payment \
    && useradd --system --uid 10001 --gid 10001 --no-create-home payment

WORKDIR /app

COPY --from=build --chown=10001:10001 /src/application.jar /app/application.jar

USER 10001:10001

EXPOSE 8003 50056


HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8003/health/ready > /dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/application.jar"]
