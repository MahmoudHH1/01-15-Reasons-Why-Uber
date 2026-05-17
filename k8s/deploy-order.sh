#!/bin/bash
# M3 Deployment Order Script (uber-m3.md §10)

echo "--- 1. Namespaces ---"
kubectl apply -f k8s/namespaces/

echo "--- 2. Secrets & ConfigMaps ---"
kubectl apply -f k8s/secrets/
kubectl apply -f k8s/configmaps/

echo "--- 3. Persistent Volumes ---"
kubectl apply -f k8s/pvcs/

echo "--- 4. Databases (StatefulSets) ---"
kubectl apply -f k8s/statefulsets/

echo "--- 5. Wait for Databases ---"
for db in user-postgres driver-postgres ride-postgres location-postgres payment-postgres mongodb redis elasticsearch neo4j cassandra rabbitmq; do
  echo "Waiting for $db..."
  kubectl wait --for=condition=ready pod -l app=$db -n uber --timeout=180s
done

echo "--- 6. Application Services ---"
kubectl apply -f k8s/services/
kubectl apply -f k8s/deployments/
kubectl apply -f k8s/api-gateway/
kubectl apply -R -f k8s/monitoring/
