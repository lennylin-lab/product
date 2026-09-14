#!/bin/bash
# robust planning launcher: retry until Started
cd /home/lenny/Projects/pps/product/product-services
J=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8/bin/java
export SPRING_DATA_REDIS_PASSWORD=123456
for attempt in 1 2 3 4 5; do
  $J -jar product-planning/target/product-planning-0.0.1-SNAPSHOT.jar > ../scratch/phase4/logs/planning.log 2>&1 &
  PID=$!
  for i in $(seq 1 40); do
    if grep -q "Started PlanningApplication" ../scratch/phase4/logs/planning.log 2>/dev/null; then
      echo "planning started PID=$PID (attempt $attempt)"
      exit 0
    fi
    if ! kill -0 $PID 2>/dev/null; then
      echo "attempt $attempt died (pid $PID), retrying after port wait..."
      for w in $(seq 1 15); do ss -tln 2>/dev/null | grep -q ":8104 " || break; sleep 1; done
      break
    fi
    sleep 1
  done
done
echo "FAILED to start planning"
exit 1
