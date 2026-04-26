FROM eclipse-temurin:25.0.2_10-jdk AS builder
WORKDIR /app

COPY pom.xml .
COPY .git ./.git

COPY common ./common
COPY user-service/pom.xml ./user-service/pom.xml
COPY driver-service/pom.xml ./driver-service/pom.xml
COPY ride-service/pom.xml ./ride-service/pom.xml
COPY location-service/pom.xml ./location-service/pom.xml
COPY payment-service/pom.xml ./payment-service/pom.xml

COPY user-service/.mvn .mvn
COPY user-service/mvnw ./
COPY user-service/src ./user-service/src

RUN chmod +x mvnw && \
    ./mvnw install -N -DskipTests && \
    ./mvnw install -pl common -DskipTests && \
    ./mvnw package -pl user-service -DskipTests

FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY --from=builder /app/user-service/target/user-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]