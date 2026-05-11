FROM eclipse-temurin:25.0.2_10-jdk AS builder
WORKDIR /app

# Root pom is needed as parent for contracts — copy it first
COPY pom.xml ./

# contracts must be installed before driver-service can compile
COPY contracts ./contracts

# driver-service sources
COPY driver-service/.mvn ./driver-service/.mvn
COPY driver-service/mvnw ./driver-service/mvnw
COPY driver-service/pom.xml ./driver-service/pom.xml
COPY driver-service/src ./driver-service/src

RUN chmod +x driver-service/mvnw && \
    driver-service/mvnw install:install-file \
        -Dfile=pom.xml \
        -DgroupId=com.team01.uber \
        -DartifactId=uber \
        -Dversion=1.0-SNAPSHOT \
        -Dpackaging=pom && \
    driver-service/mvnw install -f contracts/pom.xml -DskipTests && \
    driver-service/mvnw package -f driver-service/pom.xml -DskipTests

FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY --from=builder /app/driver-service/target/driver-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
