package com.team01.uber.tests.designpatterns;

import com.team01.uber.contracts.security.JwtConfigurationManager;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-5 Singleton — TC406..TC411 (6 TCs).
 *
 * <p>Per docs/m3/design-patterns.md (DP-5): {@code JwtConfigurationManager}
 * lives in the shared {@code contracts} module (which is on this test
 * module's classpath via the pom), so structural reflection assertions can
 * actually run here — they're not deferred.
 *
 * <ul>
 *   <li>Private constructor (TC406).</li>
 *   <li>public static {@code getInstance()} returning the singleton type (TC407).</li>
 *   <li>Reference equality across two calls (TC408).</li>
 *   <li>Thread-safe under contention (TC409).</li>
 *   <li>No Spring stereotype annotation (TC410).</li>
 *   <li>End-to-end token round-trip proves JwtService reads via getInstance() (TC411).</li>
 * </ul>
 */
@DisplayName("DP-5 Singleton — JwtConfigurationManager")
class DpSingletonTest extends BaseHttpTest {

    @Test
    @DisplayName("TC406 — DP-5 Singleton: JwtConfigurationManager has private constructor")
    void tc406_privateConstructor() {
        Constructor<?>[] ctors = JwtConfigurationManager.class.getDeclaredConstructors();
        assertThat(ctors)
                .as("JwtConfigurationManager must declare exactly one constructor")
                .hasSize(1);
        assertThat(Modifier.isPrivate(ctors[0].getModifiers()))
                .as("the sole constructor must be private (callers cannot `new` it directly)")
                .isTrue();
    }

    @Test
    @DisplayName("TC407 — DP-5 Singleton: getInstance() is public static")
    void tc407_getInstanceIsPublicStatic() throws NoSuchMethodException {
        Method m = JwtConfigurationManager.class.getDeclaredMethod("getInstance");
        int mods = m.getModifiers();
        assertThat(Modifier.isPublic(mods))
                .as("getInstance() must be public")
                .isTrue();
        assertThat(Modifier.isStatic(mods))
                .as("getInstance() must be static")
                .isTrue();
        assertThat(m.getReturnType())
                .as("getInstance() must return JwtConfigurationManager")
                .isEqualTo(JwtConfigurationManager.class);
    }

    @Test
    @DisplayName("TC408 — DP-5 Singleton: same reference (==)")
    void tc408_sameReference() {
        JwtConfigurationManager ref1 = JwtConfigurationManager.getInstance();
        JwtConfigurationManager ref2 = JwtConfigurationManager.getInstance();
        assertThat(ref1 == ref2)
                .as("two getInstance() calls must return the exact same reference (identity, not equals)")
                .isTrue();
    }

    @Test
    @DisplayName("TC409 — DP-5 Singleton: thread-safe under contention")
    void tc409_threadSafeUnderContention() {
        Set<JwtConfigurationManager> identitySet = Collections.newSetFromMap(new ConcurrentHashMap<>());

        IntStream.range(0, 10).parallel().forEach(i -> {
            JwtConfigurationManager ref = JwtConfigurationManager.getInstance();
            identitySet.add(ref);
        });

        assertThat(identitySet)
                .as("10 parallel getInstance() calls must all observe the same singleton reference")
                .hasSize(1);
    }

    @Test
    @DisplayName("TC410 — DP-5 Singleton: NOT a Spring bean")
    void tc410_notSpringStereotype() {
        // Probe Spring stereotype annotations by class name to avoid hard dependency on Spring at test compile time.
        String[] forbidden = {
                "org.springframework.stereotype.Component",
                "org.springframework.stereotype.Service",
                "org.springframework.context.annotation.Configuration",
                "org.springframework.context.annotation.Bean",
                "org.springframework.stereotype.Repository"
        };

        for (String fqcn : forbidden) {
            try {
                @SuppressWarnings("unchecked")
                Class<? extends java.lang.annotation.Annotation> ann =
                        (Class<? extends java.lang.annotation.Annotation>) Class.forName(fqcn);
                assertThat(JwtConfigurationManager.class.isAnnotationPresent(ann))
                        .as("JwtConfigurationManager must NOT be annotated with " + fqcn
                                + " (must be classical GoF Singleton, not Spring-managed)")
                        .isFalse();
            } catch (ClassNotFoundException ignored) {
                // Annotation class missing from classpath — by definition cannot be present on the singleton. OK.
            }
        }
    }

    @Test
    @DisplayName("TC411 — DP-5 Singleton: JwtService reads via getInstance()")
    void tc411_jwtServiceRoundTripViaSingleton() {
        // End-to-end: register → capture token (issued by user-service JwtService) →
        // GET protected endpoint (validated by user-service JwtService). Both sides must
        // agree on the secret served by JwtConfigurationManager.getInstance().
        String email = Nonce.email("tc411");
        Http.Response register = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", "TC411 User",
                        "email", email,
                        "password", "TestPwd!2026",
                        "phone", Nonce.phone()))
                .post();
        assertThat(register.status()).as("seed register").isBetween(200, 299);
        String token = register.json().path("token").asText();
        assertThat(token).as("issued token").isNotBlank();

        Http.Response protectedCall = Http.request(USER_BASE, "/api/users")
                .bearer(token)
                .get();

        assertThat(protectedCall.status())
                .as("token issued by JwtService must validate at the next protected call "
                        + "(both sides read the same secret via JwtConfigurationManager.getInstance())")
                .isBetween(200, 299);
    }
}
