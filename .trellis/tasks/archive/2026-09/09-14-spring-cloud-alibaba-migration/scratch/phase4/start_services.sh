#!/bin/bash
# Phase 4 live-run services (run from product-services so logback ./logs is writable)
cd /home/lenny/Projects/pps/product/product-services
J=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8/bin/java
L=../scratch/phase4/logs
export SPRING_DATA_REDIS_PASSWORD=123456
$J -jar product-identity/target/product-identity-0.0.1-SNAPSHOT.jar > $L/identity.log 2>&1 &
echo "identity $!" >> ../scratch/phase4/pids.txt
$J -jar product-master-data/target/product-master-data-0.0.1-SNAPSHOT.jar > $L/master-data.log 2>&1 &
echo "master-data $!" >> ../scratch/phase4/pids.txt
$J -jar product-demand-service/target/product-demand-service-0.0.1-SNAPSHOT.jar > $L/demand.log 2>&1 &
echo "demand $!" >> ../scratch/phase4/pids.txt
$J -jar product-planning/target/product-planning-0.0.1-SNAPSHOT.jar > $L/planning.log 2>&1 &
echo "planning $!" >> ../scratch/phase4/pids.txt
$J -jar product-gateway/target/product-gateway-0.0.1-SNAPSHOT.jar > $L/gateway.log 2>&1 &
echo "gateway $!" >> ../scratch/phase4/pids.txt
echo "--- PIDs recorded:"; cat ../scratch/phase4/pids.txt
sleep 7200
