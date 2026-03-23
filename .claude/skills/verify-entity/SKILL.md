---
name: verify-entity
description: Compare entity class definitions against the M1 specification to catch missing fields, wrong types, incorrect constraints, or relationship misconfigurations.
---

# Verify Entity Against M1 Spec

You are comparing the actual Java entity classes against the M1 specification to catch discrepancies before the auto-grader runs.

## Steps

### 1. Determine Which Entities to Check

If on a feature branch, determine the service from the branch name and check that service's entities. Otherwise, ask which service to verify.

### 2. Get the Entity Specification

Ask the user how they want to provide the spec. Present these options:

1. **PDF path** — "Provide the path to the M1 description PDF and I'll extract the entity tables"
2. **Paste it** — "Paste the entity field table(s) directly into the chat"

Wait for the user's response before proceeding. Do NOT assume a spec file exists.

### 3. Read the Actual Entity Classes

Find and read all entity classes in the service's `model/` package.

### 4. Compare Field by Field

For each entity, check:

| Check | What to verify |
|-------|---------------|
| Table name | `@Table(name = "...")` matches spec |
| All fields present | Every field from the spec exists in the class |
| No extra fields | No fields that aren't in the spec (warn, don't fail) |
| Field types | Java types match spec (String, Long, Double, LocalDateTime, etc.) |
| Nullability | `@Column(nullable = false)` where spec says "not null" |
| Uniqueness | `@Column(unique = true)` where spec says "unique" |
| Defaults | Default values set where spec specifies them |
| ID generation | `@Id` + `@GeneratedValue(strategy = GenerationType.IDENTITY)` |
| Enum fields | `@Enumerated(EnumType.STRING)` on all enum fields |
| Enum values | Enum class has exactly the values listed in spec |
| JSONB columns | `Map<String, Object>` type with proper JSONB annotations |
| Relationships | Correct `@OneToMany`/`@ManyToOne` with proper owning/inverse sides |
| @JsonIgnore | Present on inverse side of bidirectional relationships |
| Cross-service FKs | Plain `Long` type, NOT JPA relationship annotations |

### 5. Report

```
Entity Verification: <ServiceName>
───────────────────────────────────
<EntityName> (<table_name>):
  Fields:        [PASS/FAIL] — X/Y fields match spec
  Types:         [PASS/FAIL] — all types correct
  Constraints:   [PASS/FAIL] — nullability, uniqueness
  Enums:         [PASS/FAIL] — @Enumerated + correct values
  JSONB:         [PASS/FAIL] — proper Map<String, Object> + annotations
  Relationships: [PASS/FAIL] — owning/inverse sides correct
  @JsonIgnore:   [PASS/FAIL] — present on inverse sides

<Repeat for each entity>
```

For each FAIL, show:
- What the spec says
- What the code actually has
- The exact fix needed
