FROM eclipse-temurin:25.0.2_10-jdk AS builder
WORKDIR /app

COPY location-service/.mvn .mvn
COPY location-service/mvnw mvnw
RUN chmod +x mvnw

# contracts depends on the root uber pom as parent; copy both so Maven resolves the parent via relativePath
COPY pom.xml ./
COPY contracts ./contracts
RUN git init && ./mvnw -f contracts/pom.xml install -DskipTests

COPY location-service/pom.xml service-pom.xml
COPY location-service/src ./src
RUN ./mvnw -f service-pom.xml clean package -DskipTests

FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY --from=builder /app/target/location-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
