FROM maven:3.9-eclipse-temurin-11 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn package -DskipTests

FROM eclipse-temurin:11-jre-jammy

RUN apt-get update && \
    apt-get install -y \
    libxext6 \
    libx11-6 \
    libxrender1 \
    libxtst6 \
    libfreetype6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/target/SnakeGame.jar /app/SnakeGame.jar

ENTRYPOINT ["java", "-jar", "/app/SnakeGame.jar"]
