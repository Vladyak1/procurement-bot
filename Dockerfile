# Этап сборки
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src/ ./src/
RUN mvn clean package -DskipTests

# Этап выполнения
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/procurement-bot-1.0-SNAPSHOT.jar ./app.jar
COPY src/main/resources/application.properties ./config/application.properties
COPY src/main/resources/logback.xml ./config/logback.xml
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Duser.timezone=Europe/Moscow"
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
