# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Cache dependencies first
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
# Build the boot jar
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# H2 writes to ./data (jdbc:h2:file:./data/zes-tracker) -> /app/data (mounted volume)
COPY --from=build /build/target/*.jar app.jar
ENV JAVA_OPTS="-Xmx768m"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
