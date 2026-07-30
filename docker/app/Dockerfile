FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app
COPY docker/app/settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY lombok.config .
COPY src ./src

RUN mvn clean package -Dmaven.test.skip=true

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
