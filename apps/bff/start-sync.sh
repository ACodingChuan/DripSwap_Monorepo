#!/bin/bash

echo "==================================="
echo "DripSwap BFF - Subgraph Sync"
echo "==================================="
echo ""

# 检查 Docker 服务
echo "1. Checking Docker services..."
if ! docker ps | grep -q dripswap-postgres; then
    echo "   Starting PostgreSQL..."
    docker-compose up -d postgres
    sleep 5
else
    echo "   ✓ PostgreSQL is running"
fi

if ! docker ps | grep -q dripswap-redis; then
    echo "   Starting Redis..."
    docker-compose up -d redis
    sleep 2
else
    echo "   ✓ Redis is running"
fi

echo ""
echo "2. Starting BFF application..."
echo "   (This will take a moment...)"
echo ""

# 启动应用
mvn spring-boot:run &
BFF_PID=$!

# 等待应用启动
echo "3. Waiting for application to start..."
sleep 15

# 检查应用是否启动
if curl -s http://localhost:8080/api/sync/status > /dev/null 2>&1; then
    echo "   ✓ Application is ready"
else
    echo "   ⚠ Application may not be ready yet, waiting..."
    sleep 10
fi

echo ""
echo "4. Triggering full sync..."
RESPONSE=$(curl -s -X POST http://localhost:8080/api/sync/full)
echo "   Response: $RESPONSE"

echo ""
echo "==================================="
echo "Sync started!"
echo "==================================="
echo ""
echo "Monitor progress:"
echo "  - Logs: tail -f logs/spring.log"
echo "  - Database: docker exec -it dripswap-postgres psql -U dripswap -d dripswap"
echo ""
echo "Check data:"
echo "  SELECT COUNT(*) FROM tokens;"
echo "  SELECT COUNT(*) FROM pairs;"
echo "  SELECT COUNT(*) FROM swaps;"
echo ""
echo "Press Ctrl+C to stop the application"
echo ""

# 等待用户中断
wait $BFF_PID
