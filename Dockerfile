# syntax=docker/dockerfile:1

# ---- build: compile, run the test suite, produce the fat jar -----------------
FROM maven:3.9.9-eclipse-temurin-21 AS build

# AWT text rendering in the tests needs fontconfig + at least one font.
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core fonts-droid-fallback \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /src
# Resolve dependencies in their own layer so code changes don't re-download them.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

# ---- runtime: small JRE image ------------------------------------------------
FROM eclipse-temurin:21-jre-noble

# fontconfig/DejaVu: rendering Word/text pages to images.
# Droid Sans Fallback: CJK characters in PDF output (wired up via DOCSMELT_FONTS).
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core fonts-droid-fallback \
 && rm -rf /var/lib/apt/lists/*

ENV DOCSMELT_FONTS=/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf

COPY --from=build /src/target/docsmelt.jar /app/docsmelt.jar

# CLI use: mount files here, e.g. docker run -v "$PWD:/work" docsmelt a.docx a.pdf
WORKDIR /work
USER ubuntu
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-XX:MaxRAMPercentage=75", "-jar", "/app/docsmelt.jar"]
# Default: the web UI. Any arguments you pass replace this and run the CLI instead.
CMD ["serve", "--host", "0.0.0.0", "--port", "8080"]
