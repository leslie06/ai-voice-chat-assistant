# 多阶段构建：第一阶段打 jar，第二阶段只带 JRE 运行（镜像更小）
# 构建：docker build -f deploy/Dockerfile -t vca:latest .
# 运行：见 deploy/docker-compose.yml

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN ./mvnw -pl vca-bootstrap -am package -DskipTests -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-Duser.timezone=Asia/Shanghai","-jar","/app/app.jar"]
