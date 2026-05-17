package com.team01.uber.tests.crosscutting;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cutting auth — verifies that a JWT issued by user-service is accepted
 * by every other service's protected routes. Proves the §3.6 Singleton
 * (JwtConfigurationManager) shares the same secret across the 5 services.
 */
@DisplayName("Cross-cutting — JWT validation on non-User services")
class CrossServiceAuthFeatureTest extends BaseHttpTest {

    private static final String NON_USER_LIST_PATH = "/api/drivers";

    @Test
    @DisplayName("TC06 — Authentication happy path (valid admin JWT accepted on a non-User CRUD list)")
    void tc06_validAdminTokenOnNonUserCrudList_returns2xx() {
        String adminToken = Seeders.adminTokenOrNull();
        if (adminToken == null) {
            // Spec fallback: when no admin seed is reachable, fall back to a
            // freshly-registered RIDER token. The auth filter only verifies
            // signature + claim presence; role-based reject would be a 403,
            // not 401, which is still distinct from the bug TC06 targets
            // (auth filter only wired to UserController).
            adminToken = Seeders.registerRider("tc06").token();
        }

        Http.Response response = Http.request(DRIVER_BASE, NON_USER_LIST_PATH)
                .bearer(adminToken)
                .get();

        assertThat(response.status())
                .as("non-User CRUD list with valid token must pass auth filter")
                .isBetween(200, 299);
    }

    @Test
    @DisplayName("Cross-service — RIDER token from user-service is accepted by driver-service")
    void riderToken_acceptedByDriverService() {
        String token = Seeders.registerRider("ccdrv").token();

        Http.Response response = Http.request(DRIVER_BASE, "/api/drivers")
                .bearer(token)
                .get();

        // 2xx confirms shared JWT secret (§3.6 Singleton). 403 would still
        // mean auth passed but role gate refused — also acceptable here.
        assertThat(response.status())
                .as("driver-service must accept a user-service-issued JWT")
                .satisfiesAnyOf(
                        s -> assertThat(s).isBetween(200, 299),
                        s -> assertThat(s).isEqualTo(403));
    }

    @Test
    @DisplayName("Cross-service — RIDER token from user-service is accepted by ride-service")
    void riderToken_acceptedByRideService() {
        String token = Seeders.registerRider("ccride").token();

        Http.Response response = Http.request(RIDE_BASE, "/api/rides")
                .bearer(token)
                .get();

        assertThat(response.status())
                .as("ride-service must accept a user-service-issued JWT")
                .satisfiesAnyOf(
                        s -> assertThat(s).isBetween(200, 299),
                        s -> assertThat(s).isEqualTo(403));
    }

    @Test
    @DisplayName("Cross-service — RIDER token from user-service is accepted by location-service")
    void riderToken_acceptedByLocationService() {
        String token = Seeders.registerRider("ccloc").token();

        Http.Response response = Http.request(LOCATION_BASE, "/api/locations")
                .bearer(token)
                .get();

        assertThat(response.status())
                .as("location-service must accept a user-service-issued JWT")
                .satisfiesAnyOf(
                        s -> assertThat(s).isBetween(200, 299),
                        s -> assertThat(s).isEqualTo(403));
    }

    @Test
    @DisplayName("Cross-service — RIDER token from user-service is accepted by payment-service")
    void riderToken_acceptedByPaymentService() {
        String token = Seeders.registerRider("ccpay").token();

        Http.Response response = Http.request(PAYMENT_BASE, "/api/payments")
                .bearer(token)
                .get();

        assertThat(response.status())
                .as("payment-service must accept a user-service-issued JWT")
                .satisfiesAnyOf(
                        s -> assertThat(s).isBetween(200, 299),
                        s -> assertThat(s).isEqualTo(403));
    }
}
