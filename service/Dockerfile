# Сборка: Maven + JDK 11
FROM maven:3.9.9-eclipse-temurin-11 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Запуск: JRE 11
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /src/target/heatnet-service.jar /app/app.jar
RUN mkdir -p /data/upload-tmp
ENV STORAGE_DIR=/data \
    SPRING_SERVLET_MULTIPART_LOCATION=/data/upload-tmp \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
