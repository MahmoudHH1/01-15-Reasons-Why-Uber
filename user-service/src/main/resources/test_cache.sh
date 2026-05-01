#!/bin/bash

################################################################################
# User Service Cache Testing Suite (FIXED)
# Tests all cached endpoints (S1-F1, F3, F5, F6, F8, F12)
# Note: S1-F9 now has @Cacheable added to UserService
# Author: Welo5 (55-26445)
################################################################################

# ============================================================================
# CONFIGURATION
# ============================================================================

BASE_URL="http://localhost:8081"
REDIS_HOST="uber-redis"
REDIS_PORT="6379"
REDIS_PASS="redispass"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0
CACHE_HITS=0
CACHE_MISSES=0

################################################################################
# UTILITY FUNCTIONS
################################################################################

log_header() {
    echo -e "\n${BLUE}════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}\n"
}

log_section() {
    echo -e "\n${YELLOW}▶ $1${NC}\n"
}

log_success() {
    echo -e "${GREEN}✓ $1${NC}"
    ((TESTS_PASSED++))
}

log_fail() {
    echo -e "${RED}✗ $1${NC}"
    ((TESTS_FAILED++))
}

log_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# Get JWT token by logging in
get_jwt_token() {
    local email=$1
    local password=$2
    
    log_info "Getting JWT token for $email..."
    
    local response=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$email\",\"password\":\"$password\"}")
    
    local token=$(echo $response | grep -o '"token":"[^"]*' | cut -d'"' -f4)
    
    if [ -z "$token" ]; then
        log_fail "Failed to get JWT token"
        return 1
    fi
    
    echo "$token"
}

# Check Redis keys
redis_get_keys() {
    local pattern=$1
    docker exec $REDIS_HOST redis-cli -a $REDIS_PASS KEYS "$pattern" 2>/dev/null | grep -v "Warning"
}

# Check if key exists in Redis
redis_key_exists() {
    local key=$1
    local result=$(docker exec $REDIS_HOST redis-cli -a $REDIS_PASS EXISTS "$key" 2>/dev/null | grep -v "Warning")
    [ "$result" = "1" ]
}

# Flush Redis cache
redis_flush() {
    docker exec $REDIS_HOST redis-cli -a $REDIS_PASS FLUSHDB 2>/dev/null > /dev/null
    log_info "Redis cache flushed"
}

# Test endpoint caching
test_cache_hit() {
    local endpoint=$1
    local description=$2
    local cache_key=$3
    
    log_section "$description"
    log_info "Endpoint: $endpoint"
    
    # First call (cache miss)
    local start1=$(date +%s%N)
    local response1=$(curl -s -X GET "$BASE_URL$endpoint" \
        -H "Authorization: Bearer $JWT_TOKEN" \
        -H "Content-Type: application/json")
    local end1=$(date +%s%N)
    local time1=$(( (end1 - start1) / 1000000 ))
    
    log_info "First call: ${time1}ms (cache miss, query from DB)"
    
    # Verify cache key was created
    sleep 0.5
    if redis_key_exists "$cache_key"; then
        log_success "Cache key created: $cache_key"
        ((CACHE_MISSES++))
    else
        log_fail "Cache key NOT created: $cache_key"
        return 1
    fi
    
    # Second call (cache hit)
    local start2=$(date +%s%N)
    local response2=$(curl -s -X GET "$BASE_URL$endpoint" \
        -H "Authorization: Bearer $JWT_TOKEN" \
        -H "Content-Type: application/json")
    local end2=$(date +%s%N)
    local time2=$(( (end2 - start2) / 1000000 ))
    
    log_info "Second call: ${time2}ms (cache hit, served from Redis)"
    
    # Verify responses are identical
    if [ "$response1" = "$response2" ]; then
        log_success "Responses are identical"
    else
        log_fail "Responses differ between calls"
    fi
    
    log_success "Cache test passed for $description"
    ((CACHE_HITS++))
}

# Create dummy user
create_dummy_user() {
    local name=$1
    local email=$2
    local password=$3
    local phone=$4
    
    log_info "Creating user: $email..."
    
    local response=$(curl -s -X POST "$BASE_URL/api/auth/register" \
        -H "Content-Type: application/json" \
        -d "{
            \"name\":\"$name\",
            \"email\":\"$email\",
            \"password\":\"$password\",
            \"phone\":\"$phone\"
        }")
    
    local token=$(echo $response | grep -o '"token":"[^"]*' | cut -d'"' -f4)
    
    if [ -z "$token" ]; then
        log_fail "Failed to create user $email"
        return 1
    fi
    
    log_success "User created: $email"
    echo "1"  # Return dummy ID since we can't easily extract from JWT
}

################################################################################
# MAIN TEST SUITE
################################################################################

main() {
    log_header "USER SERVICE CACHE TESTING SUITE (FIXED)"
    log_info "Target: $BASE_URL"
    log_info "Redis: $REDIS_HOST:$REDIS_PORT"
    
    # ========================================================================
    # STEP 1: Setup
    # ========================================================================
    
    log_header "STEP 1: SETUP"
    
    # Flush Redis
    redis_flush
    
    # Create test users
    USER1_ID=$(create_dummy_user "Ahmed Hassan" "ahmed@test.com" "pass123" "+201011111111")
    ADMIN_ID=$(create_dummy_user "Admin User" "admin@uber.com" "admin123" "+201000000000")
    
    if [ -z "$USER1_ID" ] || [ -z "$ADMIN_ID" ]; then
        log_fail "Failed to create test users"
        exit 1
    fi
    
    # Get JWT tokens (use admin for everything since endpoints require auth)
    JWT_TOKEN=$(get_jwt_token "admin@uber.com" "admin123")
    
    if [ -z "$JWT_TOKEN" ]; then
        log_fail "Failed to get JWT token"
        exit 1
    fi
    
    log_success "Setup complete"
    
    # ========================================================================
    # STEP 2: S1-F1 — Search Users
    # ========================================================================
    
    log_header "STEP 2: S1-F1 SEARCH USERS (5 min TTL)"
    
    test_cache_hit \
        "/api/users/search?name=Admin" \
        "S1-F1: Search users by name" \
        "user-service::S1-F1::Admin-null-null"
    
    # ========================================================================
    # STEP 3: S1-F3 — Get Ride Summary
    # ========================================================================
    
    log_header "STEP 3: S1-F3 GET USER RIDE SUMMARY (10 min TTL)"
    
    test_cache_hit \
        "/api/users/1/ride-summary" \
        "S1-F3: Get ride summary for user 1" \
        "user-service::S1-F3::1"
    
    # ========================================================================
    # STEP 4: S1-F5 — Search by Preference
    # ========================================================================
    
    log_header "STEP 4: S1-F5 FILTER BY PREFERENCE (5 min TTL)"
    
    test_cache_hit \
        "/api/users/preferences/search?key=language&value=en" \
        "S1-F5: Filter users by preference" \
        "user-service::S1-F5::language-en"
    
    # ========================================================================
    # STEP 5: S1-F6 — Top Riders
    # ========================================================================
    
    log_header "STEP 5: S1-F6 TOP RIDERS (10 min TTL)"
    
    test_cache_hit \
        "/api/users/reports/top-riders?startDate=2026-03-01&endDate=2026-03-31&limit=5" \
        "S1-F6: Get top riders report" \
        "user-service::S1-F6::2026-03-01-2026-03-31-5"
    
    # ========================================================================
    # STEP 6: S1-F8 — User Profile
    # ========================================================================
    
    log_header "STEP 6: S1-F8 GET USER PROFILE (15 min TTL)"
    
    test_cache_hit \
        "/api/users/1/profile" \
        "S1-F8: Get user profile" \
        "user-service::S1-F8::1"
    
    # ========================================================================
    # STEP 7: S1-F9 — Users by Language
    # ========================================================================
    
    log_header "STEP 7: S1-F9 FIND USERS BY LANGUAGE (10 min TTL)"
    
    test_cache_hit \
        "/api/users/preferences/language?lang=en&minRides=0" \
        "S1-F9: Find users by language" \
        "user-service::S1-F9::en-0"
    
    # ========================================================================
    # STEP 8: S1-F12 — Activity Feed
    # ========================================================================
    
    log_header "STEP 8: S1-F12 GET ACTIVITY FEED (5 min TTL)"
    
    test_cache_hit \
        "/api/users/1/activity?page=0&size=10" \
        "S1-F12: Get activity feed" \
        "user-service::S1-F12::1-0-10"
    
    # ========================================================================
    # STEP 9: Summary
    # ========================================================================
    
    log_header "TEST RESULTS SUMMARY"
    
    echo -e "${BLUE}Cache Tests:${NC}"
    echo -e "  ${GREEN}Cache Hits: $CACHE_HITS${NC}"
    echo -e "  ${GREEN}Cache Misses (new entries): $CACHE_MISSES${NC}"
    echo ""
    echo -e "${BLUE}Overall Results:${NC}"
    echo -e "  ${GREEN}Passed: $TESTS_PASSED${NC}"
    echo -e "  ${RED}Failed: $TESTS_FAILED${NC}"
    
    if [ $TESTS_FAILED -eq 0 ]; then
        echo ""
        log_success "ALL TESTS PASSED!"
        
        log_header "FINAL REDIS STATE"
        log_info "Active cache keys:"
        redis_get_keys "user-service::*" | nl
        
        return 0
    else
        echo ""
        log_fail "SOME TESTS FAILED!"
        return 1
    fi
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    main "$@"
    exit $?
fi