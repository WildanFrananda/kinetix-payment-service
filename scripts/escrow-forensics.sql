SELECT r.id,
       r.operation,
       r.idempotency_key,
       r.key_source,
       r.request_fingerprint,
       r.escrow_id,
       r.detail,
       r.created_at
FROM   escrow_idempotency_records r
WHERE  r.order_number = 'ORD-1001'
ORDER  BY r.created_at;

SELECT r.operation,
       r.idempotency_key,
       count(DISTINCT r.request_fingerprint) AS payloads,
       array_agg(DISTINCT r.order_number)    AS orders,
       min(r.created_at)                     AS first_seen,
       max(r.created_at)                     AS last_seen
FROM   escrow_idempotency_records r
GROUP  BY r.operation, r.idempotency_key
HAVING count(DISTINCT r.request_fingerprint) > 1;

SELECT r.order_number,
       r.idempotency_key,
       r.created_at        AS refund_attempted_at,
       h.id                AS escrow_id,
       h.status,
       h.customer_principal_id,
       h.total_order_amount,
       h.created_at        AS hold_created_at
FROM   escrow_idempotency_records r
JOIN   escrow_holds h ON h.order_number = r.order_number
WHERE  r.operation = 'REFUND'
  AND  r.escrow_id IS NULL
ORDER  BY r.created_at;

SELECT t.reference_number,
       t.type,
       t.amount,
       t.status,
       t.created_at,
       r.operation,
       r.idempotency_key,
       r.key_source
FROM   payment_transactions t
LEFT   JOIN escrow_idempotency_records r
       ON r.order_number = regexp_replace(t.reference_number, '^escrow:[a-z]+:', '')
      AND r.operation = CASE t.type
                          WHEN 'CHECKOUT_PAYMENT' THEN 'CREATE_HOLD'
                          WHEN 'ESCROW_RELEASE'   THEN 'RELEASE'
                          WHEN 'REFUND'           THEN 'REFUND'
                        END
WHERE  t.principal_id = '<principal>'
  AND  t.reference_number LIKE 'escrow:%'
ORDER  BY t.created_at DESC;

SELECT h.order_number,
       h.customer_principal_id,
       h.merchant_principal_id,
       h.total_order_amount,
       h.created_at,
       h.auto_release_at
FROM   escrow_holds h
WHERE  h.status = 'HELD'
  AND  h.auto_release_at < now()
ORDER  BY h.auto_release_at;

SELECT r.operation,
       r.key_source,
       count(*) AS records,
       max(r.created_at) AS most_recent
FROM   escrow_idempotency_records r
GROUP  BY r.operation, r.key_source
ORDER  BY r.operation, r.key_source;
