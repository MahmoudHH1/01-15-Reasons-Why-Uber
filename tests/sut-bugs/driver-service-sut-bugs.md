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
