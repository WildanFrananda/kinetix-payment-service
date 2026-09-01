# Kinetix payment service — Java 21 / Spring Boot 3.4 / Gradle 8.12, multi-module.
#
# The build stage carries Gradle and a JDK; the runtime stage carries a JRE and
# the boot jar only. Java is pinned to 21 here because that is what `build.gradle`'s
# toolchain declares — `.java-version` said 25, which no part of the build ever used.
#
# protoc is not installed: modules/infrastructure resolves `com.google.protobuf:protoc` from
# Maven, so the protobuf compiler is a build dependency like any other and matches the
# version the generated stubs were compiled against.

# gradle:8.12-jdk21 rather than a bare JDK plus `./gradlew`: `gradle/wrapper/gradle-wrapper.jar`
# is absent from this repository, so the wrapper cannot bootstrap itself. The image pins the
# same 8.12 that gradle-wrapper.properties declares. (The missing jar is a separate repo
# defect — `./gradlew` fails for anyone cloning this repo, not only for this build.)
FROM gradle:8.12-jdk21 AS build

WORKDIR /src

# Build scripts first: the dependency resolution layer is then reused whenever only
# application sources change.
COPY settings.gradle build.gradle ./
COPY modules/domain/build.gradle ./modules/domain/
COPY modules/application/build.gradle ./modules/application/
COPY modules/infrastructure/build.gradle ./modules/infrastructure/
COPY modules/api/build.gradle ./modules/api/

RUN gradle --no-daemon dependencies --quiet || true

COPY proto ./proto
COPY modules ./modules

# Tests are not run in the image build. They need a live Postgres, which belongs to CI and
# to the compose lab, not to a build stage that must work offline from a clean checkout.
RUN gradle --no-daemon :modules:api:bootJar -x test \
    && cp modules/api/build/libs/*.jar /src/application.jar

FROM eclipse-temurin:21-jre AS final

RUN groupadd --system --gid 10001 payment \
    && useradd --system --uid 10001 --gid 10001 --no-create-home payment

WORKDIR /app

COPY --from=build --chown=10001:10001 /src/application.jar /app/application.jar

USER 10001:10001

# REST on 8003, gRPC on 50056. Neither is published to the host — the gateway reaches this
# over the `kinetix` network by service name.
EXPOSE 8003 50056

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/application.jar"]
