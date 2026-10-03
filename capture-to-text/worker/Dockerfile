# Same two-stage shape as the API's Dockerfile (build context is worker/, so
# this file sits next to worker/pom.xml): dependency resolution is its own
# cached layer ahead of `COPY src`, and only a JRE survives into the final image.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw clean package -DskipTests -B

FROM eclipse-temurin:17-jre
WORKDIR /app

# Tess4J bundles native Tesseract/Leptonica only for Windows (and macOS), NOT
# Linux -- so it works under `mvnw spring-boot:run` on a Windows dev machine but
# dies in this image with `UnsatisfiedLinkError: Unable to load library
# 'tesseract'`. The shared library has to come from the OS package instead.
# (Language data still comes from the jar via LoadLibs.extractTessResources.)
RUN apt-get update \
    && apt-get install -y --no-install-recommends libtesseract5 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

# Ops port only (health + metrics); the worker has no business endpoints.
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
