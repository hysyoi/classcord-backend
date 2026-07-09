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

# 從編譯階段複製 JAR 包到運行環境
COPY --from=build /app/target/*.jar app.jar

# 暴露 Spring Boot 的 8080 Port
EXPOSE 8080

# 啟動命令，預設啟動開發模式的 dev Profile (對接 Resend 與雲端資料庫)
ENTRYPOINT ["java", "-Dspring.profiles.active=dev", "-jar", "app.jar"]