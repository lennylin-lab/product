# product-identity 运行镜像（Phase 1 骨架）
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Taipei

WORKDIR /app
COPY target/product-identity-*.jar app.jar

EXPOSE 8101
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
