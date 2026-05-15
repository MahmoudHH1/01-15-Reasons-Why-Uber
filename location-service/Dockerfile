FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY location-service/target/location-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
