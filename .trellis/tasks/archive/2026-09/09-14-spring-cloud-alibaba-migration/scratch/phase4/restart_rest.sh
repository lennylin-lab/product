#!/bin/bash
cd /home/lenny/Projects/pps/product/product-services
J=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8/bin/java
L=../scratch/phase4/logs
export SPRING_DATA_REDIS_PASSWORD=123456
pkill -f "product-identity/target" 2>/dev/null; pkill -f "product-master-data/target" 2>/dev/null; pkill -f "product-demand-service/target" 2>/dev/null; pkill -f "product-gateway/target" 2>/dev/null
sleep 4
$J -jar product-identity/target/product-identity-0.0.1-SNAPSHOT.jar > $L/identity.log 2>&1 &
$J -jar product-master-data/target/product-master-data-0.0.1-SNAPSHOT.jar > $L/master-data.log 2>&1 &
$J -jar product-demand-service/target/product-demand-service-0.0.1-SNAPSHOT.jar > $L/demand.log 2>&1 &
$J -jar product-gateway/target/product-gateway-0.0.1-SNAPSHOT.jar > $L/gateway.log 2>&1 &
echo "restarted identity/master-data/demand/gateway"
sleep 7200
