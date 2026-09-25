# Build stage: has the JDK + Maven wrapper, discarded from the final image.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy only the files the dependency-resolution step needs first, so Docker's
# layer cache keeps "download the internet" cached across builds where only
# src/ changed (the common case while iterating).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Run stage: JRE only, no build tooling, smaller attack surface and image size.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
