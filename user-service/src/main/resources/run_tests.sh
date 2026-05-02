#!/bin/bash

################################################################################
# Cache Test Suite Setup and Runner
# Prepares environment and executes comprehensive cache tests
################################################################################

set -e

echo "🚀 User Service Cache Testing Suite"
echo "=================================="
echo ""

# Make the test script executable
chmod +x ./test_cache.sh


# Check if Docker is running
echo "✓ Checking Docker..."
if ! docker ps > /dev/null 2>&1; then
    echo "✗ Docker is not running. Please start Docker and try again."
    exit 1
fi

# Check if services are running
echo "✓ Checking services..."

if ! docker ps | grep -q "user-service"; then
    echo "✗ user-service is not running. Start it with: docker-compose up -d"
    exit 1
fi

if ! docker ps | grep -q "uber-redis"; then
    echo "✗ Redis is not running. Start it with: docker-compose up -d"
    exit 1
fi

if ! docker ps | grep -q "postgres"; then
    echo "✗ PostgreSQL is not running. Start it with: docker-compose up -d"
    exit 1
fi

echo "✓ All services running"
echo ""

# Wait for services to be fully ready
echo "⏳ Waiting for services to be ready (10 seconds)..."
sleep 10

# Run the tests
echo "📋 Running cache tests..."
echo ""

bash ./test_cache.sh
exit $?