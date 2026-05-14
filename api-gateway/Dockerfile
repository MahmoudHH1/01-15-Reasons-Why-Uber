# Build stage
FROM eclipse-temurin:25.0.2_10-jdk AS builder
WORKDIR /app

# Copy files and list them for debugging
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .
COPY src src

# Run the build
RUN chmod +x mvnw && ./mvnw clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY --from=builder /app/target/api-gateway-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]