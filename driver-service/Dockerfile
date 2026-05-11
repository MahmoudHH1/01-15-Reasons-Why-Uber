FROM eclipse-temurin:25.0.2_10-jdk AS builder
# /build has NO pom.xml — Maven never treats it as a project root
WORKDIR /build

# Store root pom under a non-default name so Maven ignores it as a project
COPY pom.xml root-pom.xml

COPY contracts ./contracts
COPY driver-service/.mvn ./driver-service/.mvn
COPY driver-service/mvnw ./driver-service/mvnw
COPY driver-service/pom.xml ./driver-service/pom.xml
COPY driver-service/src ./driver-service/src

RUN chmod +x driver-service/mvnw && \
    # Install root pom into .m2 — no pom.xml in /build so no module validation
    driver-service/mvnw install:install-file \
        -Dfile=root-pom.xml \
        -DgroupId=com.team01.uber \
        -DartifactId=uber \
        -Dversion=1.0-SNAPSHOT \
        -Dpackaging=pom && \
    # contracts uses <relativePath/> so it resolves parent from .m2, not filesystem
    driver-service/mvnw install -f contracts/pom.xml -DskipTests && \
    driver-service/mvnw package -f driver-service/pom.xml -DskipTests

FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY --from=builder /build/driver-service/target/driver-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
