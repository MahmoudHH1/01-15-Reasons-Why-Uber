FROM eclipse-temurin:25.0.2_10-jdk AS builder
WORKDIR /app

# Root pom gives Maven the multi-module context
COPY pom.xml ./

# contracts must be installed before driver-service can compile
COPY contracts ./contracts

# driver-service sources
COPY driver-service/.mvn ./driver-service/.mvn
COPY driver-service/mvnw ./driver-service/mvnw
COPY driver-service/pom.xml ./driver-service/pom.xml
COPY driver-service/src ./driver-service/src

RUN chmod +x driver-service/mvnw && \
    driver-service/mvnw install -pl contracts -DskipTests && \
    driver-service/mvnw package -pl driver-service -DskipTests

FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY --from=builder /app/driver-service/target/driver-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
