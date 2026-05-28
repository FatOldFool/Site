FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

COPY src /app/src
COPY web /app/web

RUN javac src/SiteServer.java

EXPOSE 8080

CMD ["java", "-cp", "src", "SiteServer"]