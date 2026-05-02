#!/usr/bin/env bash
# Health check smoke tests — Sec 5.4 + per-service health rules.
# Every health endpoint is public (no JWT) and must return 200 "OK".
#
# Spec: §9.1 CC-1 lists health checks as the only protected-set exemption
#       beyond /api/auth/register and /api/auth/login.

source "$(dirname "$0")/lib/common.sh"

section "00 Health checks (5 services × public 200)"

http GET "$USER_URL/api/users/health"
assert_status 200 "GET /api/users/health (no token)"
assert_body_contains "OK" "user-service health body"

http GET "$DRIVER_URL/api/drivers/health"
assert_status 200 "GET /api/drivers/health (no token)"
assert_body_contains "OK" "driver-service health body"

http GET "$RIDE_URL/api/rides/health"
assert_status 200 "GET /api/rides/health (no token)"
assert_body_contains "OK" "ride-service health body"

http GET "$LOCATION_URL/api/locations/health"
assert_status 200 "GET /api/locations/health (no token)"
assert_body_contains "OK" "location-service health body"

http GET "$PAYMENT_URL/api/payments/health"
assert_status 200 "GET /api/payments/health (no token)"
assert_body_contains "OK" "payment-service health body"
