# payment-service — SUT bugs

The original diagnosis pass on `tests/50-payment-service.sh` decomposed the
20 reported failures into:

- 9 test bugs (date-window math against a `now()`-stamped column / wrong
  Coupon CRUD payload). Fixed in `tests/50-payment-service.sh`.
- 1 already-fixed test bug (`refundSurgeIncluded=false` `tostring` coercion
  was applied in a previous test edit; the failure shown was from a stale
  run).
- 10 originally skipped due to in-flight SUT work on payment dashboards /
  audit-event emission. The Mongo `payment_audit_trail` collection currently
  contains CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED,
  ANALYTICS_VIEWED, RETRY_ATTEMPTED — the action vocabulary matches the
  spec verbatim. Most of those skips became PASSes after the SKIP-to-FAIL
  conversion because the SUT had stabilised. **One genuine SUT bug
  remains** (added below).

## §4.5 / §7.1.6 — S5-F5 (Apply Coupon) does not emit COUPON_APPLIED to `payment_audit_trail`
**Test:** `tests/50-payment-service.sh:558-560` — `fail "S5-F5 emits COUPON_APPLIED (§4.5)"`
**Spec quotes:**
> §4.5 — Composition workflow / Test scenario step d: "Call M1 S5-F5 (Apply Coupon to Payment) → event in **payment_audit_trail**."
> (Uber_descriptionM2.pdf §4.5, p. 19–20)
>
> §7.1.6 PaymentAuditEvent — "**action primary values:** CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, ANALYTICS_VIEWED. **Non-exhaustive** — extend for M1 retrofits, e.g., **COUPON_APPLIED (S5-F5)**, RETRY_ATTEMPTED (S5-F7), PAYMENT_DELETED (CRUD). Use UPPER_SNAKE_CASE."
> (Uber_descriptionM2.pdf §7.1.6, p. 28)
>
> `docs/m2/event-actions.md` — payment-service action vocabulary lists `COUPON_APPLIED` as the canonical S5-F5 action string.

**Observed:** After `POST /api/payments/{paymentId}/coupons/{couponId}` (the S5-F5 happy-path call) the `payment_audit_trail` collection has no document with `paymentId={PID}, action='COUPON_APPLIED'`, even after polling for 10 s. The CRUD POST itself returns 2xx and the join row appears in PostgreSQL — only the audit log is missing. Sibling actions on the same Payment row (CREATED, COMPLETED, REFUNDED, RETRY_ATTEMPTED) all land correctly, so the Observer chain itself works; the S5-F5 controller/service path simply skips the `notifyObservers("COUPON_APPLIED", payload)` step.

**Expected per spec:** `POST /api/payments/{paymentId}/coupons/{couponId}` must emit a COUPON_APPLIED event to `payment_audit_trail` via the same Observer→Factory chain that the other write paths use. The event's `action` field is COUPON_APPLIED (per the §7.1.6 non-exhaustive extension and `docs/m2/event-actions.md`); `method` and `amount` are NOT required on this row (§7.1.6 method-and-amount rule explicitly excludes COUPON_APPLIED from the payment-shaped set).

**Likely location:** `payment-service/src/main/java/com/team01/uber/payment/service/PaymentCouponService.java` (or wherever the apply-coupon flow lives) — after the PG insert/update succeeds, call `eventPublisher.notifyObservers("COUPON_APPLIED", Map.of("paymentId", paymentId, "couponId", couponId, "discountApplied", discount))`. There is an in-flight commit `b8dd12d fix(payment): wire COUPON_APPLIED Observer to PaymentCouponService S5-F5 retrofit (55-0664)` already merged to main — verify it actually ran on the deployed JAR (the test is hitting a still-stale image if the rebuild was skipped).
