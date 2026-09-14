# product-demand（模块 product-demand-service）运行镜像（Phase 1 骨架）
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Taipei

WORKDIR /app
COPY target/product-demand-service-*.jar app.jar

EXPOSE 8103
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
