# Missing Implementation Items — Milestone 2 Audit

This table summarizes the features and requirements from the [M2 PDF Audit](M2_Excel_vs_PDF_Audit.md) that are currently missing or incomplete in the codebase.

| Service | Category | Audit Ref | Missing Item | Status |
| :--- | :--- | :--- | :--- | :--- |
| **User Service** | Database | A1 | Seed Admin User | Missing in `schema.sql`. Need `INSERT INTO users ...` for at least one ADMIN. |
| **Location Service** | Architecture | A9 | `MongoDocumentAdapter` | File missing in `adapter` package. Required for DP-7 pattern. |
| **Location Service** | Reliability | A6 | Soft-dependency Try/Catch | `MongoEventLogger.onEvent` lacks `try/catch` to swallow Mongo exceptions. |
| **Ride Service** | Feature | S3-F11 | Interaction Logic (Neo4j) | `RideService` and `RideController` lack endpoints/methods for Neo4j interaction recording. |
| **Ride Service** | Feature | S3-F12 | Recommendations Logic (Neo4j) | `RideService` and `RideController` lack logic for Neo4j-based driver recommendations. |
| **Cross-Cutting** | Infrastructure | C2 | Specific Memory Caps | `docker-compose.yaml` should use verbatim memory limit strings from §6.2. |
| **Cross-Cutting** | Infrastructure | C3 | Healthcheck Commands | `docker-compose.yaml` missing specific `healthcheck` command strings for Mongo, ES, Redis, etc. |

---

## Detailed Notes per Service

### User Service
- **Seed Admin:** The audit (§4.2) requires seeding at least one user with the `ADMIN` role. Current `schema.sql` only defines the schema without data.

### Location Service
- **MongoDocumentAdapter:** Every service needs an adapter to convert NoSQL documents to DTOs. Location service is currently the only one without it.
- **Soft-dependency Policy:** §3.3 requires that NoSQL failures do not crash the primary PostgreSQL transaction. `MongoEventLogger` in this service does not yet wrap the `save()` call in a try/catch.

### Ride Service
- **Neo4j Features:** While DTOs like `DriverRecommendationDTO` and `Neo4jRecordAdapter` exist, the actual service methods to query Neo4j for S3-F11 (Interactions) and S3-F12 (Recommendations) are not yet implemented.

### Infrastructure
- **Docker Compose:** The grader literally checks for container names, credentials, and healthcheck commands specified in §6.4. These should be verified against the `docker-compose.yaml`.
