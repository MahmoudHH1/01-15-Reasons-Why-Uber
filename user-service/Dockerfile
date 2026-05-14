FROM eclipse-temurin:25.0.2_10-jdk AS builder
WORKDIR /app

COPY user-service/.mvn .mvn
COPY user-service/mvnw ./mvnw
COPY pom.xml ./
COPY contracts ./contracts
COPY user-service ./user-service

# Force recompile by checking if new files exist
RUN ls -la user-service/src/main/java/com/team01/uber/user/config/RabbitMQ* || echo "Checking for RabbitMQ config"

RUN apt-get update -qq && apt-get install -y -qq python3 && python3 -c "import re; f=open('pom.xml','r'); c=f.read(); f.close(); c=re.sub(r'<plugin>\s*<groupId>com\\.rudikershaw\\.gitbuildhook</groupId>.*?</plugin>','',c,flags=re.DOTALL); c=c.replace('<module>driver-service</module>',''); c=c.replace('<module>ride-service</module>',''); c=c.replace('<module>location-service</module>',''); c=c.replace('<module>payment-service</module>',''); c=c.replace('<module>api-gateway</module>',''); f=open('pom.xml','w'); f.write(c); f.close()"

RUN chmod +x mvnw && ./mvnw install -DskipTests -pl contracts -am && ./mvnw clean package -DskipTests -pl user-service

FROM eclipse-temurin:25.0.2_10-jdk
WORKDIR /app
COPY user-service/target/user-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
