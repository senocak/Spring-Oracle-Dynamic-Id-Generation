# Build stage
#FROM --platform=linux/amd64 gradle:8-jdk21 AS build
FROM gradle:8-jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle clean build -PskipTests=unit,integration

# Package stage
FROM openjdk:21-jdk-slim
RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/sko-0.0.1.jar /app/app.jar
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/app.jar"]