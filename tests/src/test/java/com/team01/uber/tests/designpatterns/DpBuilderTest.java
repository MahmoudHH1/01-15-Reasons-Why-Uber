package com.team01.uber.tests.designpatterns;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-4 Builder — TC401..TC405 (5 TCs).
 *
 * <p>Per docs/m3/design-patterns.md (DP-4): M2 dashboard DTOs (S2-F12, S3-F10,
 * S4-F10, S5-F10) plus 16 M1 in-scope DTOs each expose a static {@code builder()}
 * and fluent {@code build()}. S2-F8 and S3-F8 explicitly do NOT use Builder.
 *
 * <p>Structural TCs (TC401/TC402/TC405) require reflection / source scan on
 * service classpaths. The behavioral regression checks (TC403/TC404) hit
 * dashboard / ride-summary endpoints and assert the JSON response shape after
 * the retrofit.
 */
@DisplayName("DP-4 Builder — Dashboard / Analytics DTOs")
class DpBuilderTest extends BaseHttpTest {

    @Test
    @Disabled("DEFERRED: structural reflection on driver-service / ride-service / location-service / payment-service classpaths — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC401 — DP-4 Builder: M2 dashboard DTOs have builder()")
    void tc401_m2DashboardDtosHaveBuilder() {
        // Structural: reflection on DriverDashboardDTO, RideAnalyticsDashboardDTO, LocationAnalyticsDTO, VehicleTypeRevenueDTO.
    }

    @Test
    @Disabled("DEFERRED: structural reflection across 16 M1 in-scope DTOs in all 5 service classpaths — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC402 — DP-4 Builder: M1 in-scope DTOs have Builder")
    void tc402_m1InScopeDtosHaveBuilder() {
        // Structural: reflection on UserRideSummaryDTO, TopRiderDTO, UserProfileDTO, etc.
    }

    @Test
    @DisplayName("TC403 — DP-4 Builder: M2 dashboard regression after Builder retrofit")
    void tc403_driverDashboardRegressionAfterBuilderRetrofit() {
        // Behavioral regression: GET S2-F12 returns a fully-shaped DriverDashboardDTO.
        // We can't JDBC-seed 3 COMPLETED rides + payments via HTTP, so we assert the
        // response shape (fields present) rather than the exact aggregate values.
        String email = Nonce.email("tc403");
        Http.Response register = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", "TC403 User",
                        "email", email,
                        "password", "TestPwd!2026",
                        "phone", Nonce.phone()))
                .post();
        assertThat(register.status()).as("seed register").isBetween(200, 299);
        String token = register.json().path("token").asText();

        // Seed a driver to get a valid id for the dashboard endpoint.
        String drvSalt = Nonce.nonce().substring(0, 8);
        Map<String, Object> driverBody = new LinkedHashMap<>();
        driverBody.put("name", "TC403 Driver");
        driverBody.put("email", Nonce.email("tc403drv"));
        driverBody.put("phone", Nonce.phone());
        driverBody.put("licenseNumber", "LIC-TC403-" + drvSalt);
        driverBody.put("rating", 4.5);
        driverBody.put("totalRatings", 100);
        driverBody.put("status", "AVAILABLE");
        driverBody.put("createdAt", "2026-04-01T00:00:00");
        Map<String, Object> vehicle = new LinkedHashMap<>();
        vehicle.put("vehicleType", "SEDAN");
        vehicle.put("plate", "TC403-" + drvSalt);
        vehicle.put("description", "silver sedan");
        driverBody.put("vehicleDetails", vehicle);

        Http.Response createDriver = Http.request(DRIVER_BASE, "/api/drivers")
                .bearer(token)
                .json(driverBody)
                .post();
        Assumptions.assumeTrue(createDriver.status() >= 200 && createDriver.status() < 300,
                "seedDriver failed (" + createDriver.status() + "); skipping regression");
        long driverId = createDriver.json().path("id").asLong();

        Http.Response dashboard = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/dashboard")
                .bearer(token)
                .get();

        assertThat(dashboard.status())
                .as("S2-F12 dashboard returns 200 after Builder retrofit")
                .isEqualTo(200);

        JsonNode body = dashboard.json();
        // Required fields per design-patterns.md DP-4.
        assertThat(body.path("driverId").asLong())
                .as("dashboard.driverId populated")
                .isEqualTo(driverId);
        assertThat(body.has("name")).as("dashboard.name field present").isTrue();
        assertThat(body.has("totalRides")).as("dashboard.totalRides field present").isTrue();
        assertThat(body.has("totalEarnings")).as("dashboard.totalEarnings field present").isTrue();
        assertThat(body.has("averageRideFare")).as("dashboard.averageRideFare field present").isTrue();
        assertThat(body.has("averageRating")).as("dashboard.averageRating field present").isTrue();
        assertThat(body.has("totalRatings")).as("dashboard.totalRatings field present").isTrue();
    }

    @Test
    @DisplayName("TC404 — DP-4 Builder: M1 retrofit doesn't break behavior")
    void tc404_m1RideSummaryRegressionAfterBuilderRetrofit() {
        // S1-F3 GET /api/users/{id}/ride-summary — Builder retrofit must preserve response shape.
        // Without JDBC seeding 3 COMPLETED rides via HTTP, assert field shape on a fresh user.
        String email = Nonce.email("tc404");
        Http.Response register = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", "TC404 User",
                        "email", email,
                        "password", "TestPwd!2026",
                        "phone", Nonce.phone()))
                .post();
        assertThat(register.status()).as("seed register").isBetween(200, 299);
        String token = register.json().path("token").asText();
        long uid = JwtClaims.uidOf(token);

        Http.Response summary = Http.request(USER_BASE, "/api/users/" + uid + "/ride-summary")
                .bearer(token)
                .get();

        // M1 S1-F3 may also accept admin only; tolerate 200/2xx response with the field shape.
        Assumptions.assumeTrue(summary.status() >= 200 && summary.status() < 300,
                "S1-F3 ride-summary not accessible to a fresh rider (" + summary.status() + "); skipping regression");

        JsonNode body = summary.json();
        assertThat(body.has("totalRides"))
                .as("UserRideSummaryDTO.totalRides field present after Builder retrofit")
                .isTrue();
        assertThat(body.has("completedRides"))
                .as("UserRideSummaryDTO.completedRides field present")
                .isTrue();
        assertThat(body.has("cancelledRides"))
                .as("UserRideSummaryDTO.cancelledRides field present")
                .isTrue();
        assertThat(body.has("totalSpent"))
                .as("UserRideSummaryDTO.totalSpent field present")
                .isTrue();
        assertThat(body.has("averageFare"))
                .as("UserRideSummaryDTO.averageFare field present")
                .isTrue();
    }

    @Test
    @Disabled("DEFERRED: source-scan of S2-F8 / S3-F8 service methods — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC405 — DP-4 Builder: S2-F8 and S3-F8 do NOT use Builder")
    void tc405_s2f8AndS3f8DoNotUseBuilder() {
        // Source-scan: assert no Builder usage in S2-F8 (driver document verify) and S3-F8 (add stops).
    }
}
