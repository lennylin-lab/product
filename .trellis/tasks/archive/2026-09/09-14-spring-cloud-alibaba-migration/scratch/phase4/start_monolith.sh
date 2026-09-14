#!/bin/bash
cd /home/lenny/Projects/pps/product
export SPRING_DATA_REDIS_PASSWORD=123456
export SERVER_PORT=8082
export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8
$JAVA_HOME/bin/java -version 2>&1 | head -1
mvn -B -ntp -pl product-server spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8082" > scratch/phase4/logs/monolith.log 2>&1
