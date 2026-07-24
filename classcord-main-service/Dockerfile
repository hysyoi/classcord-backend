# syntax=docker/dockerfile:1

# 第一階段：編譯專案
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# 複製全專案 POM 檔以進行模組依賴分析
COPY pom.xml .
COPY classcord-common/pom.xml classcord-common/
COPY classcord-main-service/pom.xml classcord-main-service/
COPY classcord-ai-service/pom.xml classcord-ai-service/
COPY classcord-gateway/pom.xml classcord-gateway/

# 複製依賴的 common 與 main-service 模組原始碼
COPY classcord-common classcord-common
COPY classcord-main-service classcord-main-service

# 使用 BuildKit 快取掛載 ~/.m2，實現極速增量編譯
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -pl classcord-main-service -am -DskipTests -B

# 第二階段：極簡 JRE 運行環境
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# JVM 容器化雲原生最佳化參數 (動態適應容器 RAM 限制 75% 與 G1 垃圾回收)
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# 建立非特權使用者與群組以符合資安規範
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build --chown=appuser:appgroup /app/classcord-main-service/target/*.jar app.jar

EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8081/actuator/health || exit 1

USER appuser

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
