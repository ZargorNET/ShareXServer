FROM openjdk:8-jre

WORKDIR /sharexserver
VOLUME /sharexserver

COPY build/libs/ShareXServer-1.0-SNAPSHOT.jar /ShareXServer.jar

CMD ["java", "-jar", "/ShareXServer.jar"]