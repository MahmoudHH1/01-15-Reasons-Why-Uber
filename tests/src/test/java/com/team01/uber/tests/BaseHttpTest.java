package com.team01.uber.tests;

/**
 * Base for HTTP-level tests against the running docker-compose stack.
 *
 * <p>Tests hit per-service ports defined in docker-compose.yaml (M2 layout):
 * user 8081, driver 8082, ride 8083, location 8084, payment 8085. Once api-gateway
 * is wired into compose, swap to {@code http://localhost:30080}.
 *
 * <p>Run with the stack already up: {@code docker compose up -d && mvn -pl tests test}.
 * Override via {@code -Dservice.user.base=http://host:port}.
 */
public abstract class BaseHttpTest {

    public static final String USER_BASE     = System.getProperty("service.user.base",     "http://localhost:8081");
    public static final String DRIVER_BASE   = System.getProperty("service.driver.base",   "http://localhost:8082");
    public static final String RIDE_BASE     = System.getProperty("service.ride.base",     "http://localhost:8083");
    public static final String LOCATION_BASE = System.getProperty("service.location.base", "http://localhost:8084");
    public static final String PAYMENT_BASE  = System.getProperty("service.payment.base",  "http://localhost:8085");
}
