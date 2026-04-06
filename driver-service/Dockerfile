FROM eclipse-temurin:25.0.2_10-jdk AS builder
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src ./src
RUN chmod +x mvnw && ./mvnw clean package -DskipTests

FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY --from=builder /app/target/driver-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
