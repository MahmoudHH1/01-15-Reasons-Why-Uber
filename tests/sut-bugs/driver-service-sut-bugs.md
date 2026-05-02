# driver-service — SUT bugs found by the test suite

Each entry maps a failing assertion in `tests/20-driver-service.sh` to a verbatim
spec citation. SUT code lives under `driver-service/src/main/...` and must be
fixed by the driver-service team — the test suite does not modify SUT sources.

## §10.2.1 — Full-Text Driver Search returns 500 on every request
**Test:** `tests/20-driver-service.sh:135-155` — `assert_status 200 "S2-F10 query=toyota"` and the four sibling filter cases (`vehicleType=SEDAN`, `status=AVAILABLE`, `minRating=4.5`, nonsense query).
**Spec quote:**
> §10.2.1 Behavior — "f) Return the matching drivers with status **code 200**. Return an empty list if no matches."
> (Uber_descriptionM2.pdf §10.2.1, p. 36)

**Observed:** Every call to `GET /api/drivers/search/full-text` (with or without filters, with or without matches) responds with HTTP 500 and `{"error":"Internal Server Error","path":"/api/drivers/search/full-text"}`. Driver-service log shows the underlying Elasticsearch error:
```
co.elastic.clients.elasticsearch._types.ElasticsearchException:
  [es/search] failed: [media_type_header_exception]
  Invalid media-type value on headers [Accept, Content-Type]
```
The ES Java client (9.2.6) is sending `application/vnd.elasticsearch+json; compatible-with=9` against the running Elasticsearch 8.19.12 cluster, and the server rejects the request before the search runs.

**Expected per spec:** Endpoint must return 200 with a JSON list (possibly empty) for every call where the JWT is valid.

**Likely location:** `driver-service/src/main/java/com/team01/uber/driver/repository/DriverSearchEsRepository.java` (uses `co.elastic.clients`/`ElasticsearchOperations`) — or, more likely, the ES client dependency in `driver-service/pom.xml` needs to be downgraded to a `compatible-with=8` line (or the ES image needs to be bumped to 9.x to match the client). The fix is at the `pom.xml` / client-config level, not in the search-builder code itself; the search-builder code is correct but the wire protocol it negotiates is incompatible with the running ES.

This single root cause produces failures #3 / #4 / #5 / #6 / #7 in the test report (all five §10.2.1 assertions hit the same 500).

## §4.4.2 — DriverDocument cache key shape deviates from `<service>::<entity>::<id>`
**Test:** `tests/20-driver-service.sh:378` — `redis_count_keys "driver-service::driver-document::$DOC_ID"`
**Spec quote:**
> §4.4 / cache-matrix — "GET /api/<entity>/{id} cached — TTL 15 min — key `<service>::<entity>::<id>`"
> (`docs/m2/cache-matrix.md`, M1 CRUD GET-by-ID Cached section)

**Observed:** `getDocumentById` annotates `@Cacheable(value = "driver-service::driver-document", key = "#driverId + ':' + #docId")`, which produces Redis keys of the form `driver-service::driver-document::<driverId>:<docId>` (composite), not `driver-service::driver-document::<docId>`. The test queries the spec-correct `<docId>`-only key and gets zero hits, even though caching is in fact happening.

**Expected per spec:** The entity-detail key must be `<service>::<entity>::<id>` — driver-document's `<id>` is the document's own primary key, not a composite.

**Likely location:** `driver-service/src/main/java/com/team01/uber/driver/service/DriverDocumentService.java:86` — change the `@Cacheable` `key` SpEL from `"#driverId + ':' + #docId"` to `"#docId"`. The matching `cacheInvalidator.deleteKey(...)` calls at lines 100, 111, 155 must also drop the `<driverId>:` prefix.

## §4.4.4 — DASHBOARD_VIEWED action wrongly triggers wildcard cache invalidation
**Test:** `tests/20-driver-service.sh:336-338` — `fail "DASHBOARD_VIEWED does NOT invalidate driver entity cache (§4.4.4 obs-only)"`
**Spec quote:**
> §4.4.4 — "**Pure observability actions do NOT invalidate caches** — specifically ANALYTICS_VIEWED and DASHBOARD_VIEWED. These actions are written by the four dashboard endpoints (S2-F12, S3-F10, S4-F10, S5-F10) on every invocation (including cache hits; see §10) **purely for audit-trail / usage-telemetry purposes**. They do not change what the analytics queries return. Triggering wildcard invalidation on them would create a self-defeating cycle: every cached dashboard call would log an observability event, which would wildcard-invalidate its own key, guaranteeing that the next call is always a miss and the cache never serves a hit. **Exclude both ANALYTICS_VIEWED and DASHBOARD_VIEWED from the invalidation trigger in the Observer (match on the action field before invalidating).**"
> (Uber_descriptionM2.pdf §4.4.4, p. 18)

**Observed:** After two consecutive `GET /api/drivers/{id}/dashboard` calls (which each emit DASHBOARD_VIEWED to driver_events), the entity-detail cache key `driver-service::driver::{id}` (populated earlier by the test) is gone. The Observer/`CacheInvalidationService` is invalidating on every event regardless of action — including the pure-observability actions the spec explicitly excludes.

**Expected per spec:** DASHBOARD_VIEWED (and ANALYTICS_VIEWED) writes to MongoDB must NOT trigger any Redis wildcard delete. Only data-mutating actions (DRIVER_CREATED / UPDATED / DELETED, INDEXED, AVAILABILITY_UPDATED, RATING_RECORDED, DOCUMENT_VERIFIED, etc.) should invalidate the corresponding `driver-service::driver::{id}` key.

**Likely location:** `driver-service/src/main/java/com/team01/uber/driver/observer/MongoEventLogger.java` (or wherever the cache-invalidation hook is wired into the Observer chain). Add an early-return:
```java
if (action.equals("DASHBOARD_VIEWED") || action.equals("ANALYTICS_VIEWED")) {
    // Pure-observability action — write the audit doc but DO NOT invalidate caches (§4.4.4)
    repository.save(event);
    return;
}
```
or equivalently match on the action string before reaching `cacheInvalidator.invalidate(...)`. Without this gate, S2-F12 cannot serve a single cache hit — every dashboard call wipes its own cache.
