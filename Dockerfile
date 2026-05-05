# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/SearchEngine-1.0-SNAPSHOT.jar app.jar
CMD ["sh", "-c", "java -Xmx384m -jar app.jar"]
