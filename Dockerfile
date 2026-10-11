
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN chmod +x mvnw && ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/scaling-lab-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

# ENTRYPOINT ["java", "-XX:ActiveProcessorCount=1", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]

# Let's remove the forced one-CPU setting and allow Java 21 to detect the container's CPU allocation.


ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]

