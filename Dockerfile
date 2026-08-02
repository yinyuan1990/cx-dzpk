# 基础镜像用服务器上已有的 eclipse-temurin:17-jre-alpine(国内拉不动 Docker Hub,勿换成需要外网拉取的镜像)
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY cx-dzpk.jar /app/app.jar

EXPOSE 9100

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
