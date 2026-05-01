# payment-service — SUT bugs

No spec-violating SUT bugs were identified during the diagnosis pass on
`tests/50-payment-service.sh`. The 20 reported failures decomposed into:

- 9 test bugs (date-window math against a `now()`-stamped column / wrong
  Coupon CRUD payload). Fixed in `tests/50-payment-service.sh`.
- 1 already-fixed test bug (`refundSurgeIncluded=false` `tostring` coercion
  was applied in a previous test edit; the failure shown was from a stale
  run).
- 10 skipped due to in-flight SUT work on payment dashboards / audit-event
  emission. The Mongo `payment_audit_trail` collection currently contains
  CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, ANALYTICS_VIEWED,
  RETRY_ATTEMPTED — the action vocabulary matches the spec verbatim, so
  these tests will pass once the SUT-side dashboard rewrite stabilises.
