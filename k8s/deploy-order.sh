#!/bin/bash
# M3 Deployment Order Script (uber-m3.md §10)

echo "--- 1. Namespaces ---"
kubectl apply -f k8s/monitoring/namespace.yaml 2>/dev/null || kubectl create namespace monitoring
kubectl apply -f k8s/uber-namespace.yaml 2>/dev/null || kubectl create namespace uber

echo "--- 2. Secrets & ConfigMaps ---"
kubectl apply -f k8s/secrets/
kubectl apply -f k8s/monitoring/prometheus/prometheus-config.yaml
kubectl apply -f k8s/monitoring/grafana/grafana-datasources.yaml
kubectl apply -f k8s/monitoring/grafana/grafana-dashboards.yaml

echo "--- 3. Persistent Volumes ---"
kubectl apply -f k8s/pvcs/

echo "--- 4. Databases (StatefulSets) ---"
kubectl apply -f k8s/statefulsets/

echo "--- 5. Monitoring Infrastructure ---"
kubectl apply -f k8s/monitoring/grafana/grafana-deployment.yaml
kubectl apply -f k8s/monitoring/grafana/grafana-service.yaml

echo "--- 6. Wait for Databases ---"
echo "Waiting for Cassandra..."
kubectl wait --for=condition=ready pod/cassandra-0 -n uber --timeout=300s

echo "--- 7. Application Services ---"
# Note: Services should be applied after databases are ready
kubectl apply -f k8s/services/
