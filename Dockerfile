# 第一階段：編譯專案
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# 先複製 pom.xml 下載依賴，加速後續 cache
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 複製原始碼並編譯 JAR 包
COPY src ./src
RUN mvn clean package -DskipTests -B

# 第二階段：運行環境
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 1. 建立非 root 的群組 (appgroup) 與使用者 (appuser)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# 2. 複製 JAR 包，同時將該檔案的主人設定為 appuser
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar

# 3. 暴露 8080 Port
EXPOSE 8080

# 4. 透過 Actuator 進行健康檢查
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 5. 切換為非特權使用者 appuser，後續指令都以此身份執行
USER appuser

# 6. 啟動命令
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]