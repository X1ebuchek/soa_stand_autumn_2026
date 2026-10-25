FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -B -DskipTests dependency:resolve
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/orders.jar /app/orders.jar
COPY --from=build /src/target/lib /app/lib
EXPOSE 8080
ENTRYPOINT ["java","-Xms64m","-Xmx512m","-XX:+ExitOnOutOfMemoryError","-cp","/app/orders.jar:/app/lib/*","orders.Main"]
