# CLAUDE.md — kinetix-payment-service

The workspace `AGENTS.md` holds the stack and the verification commands. This file holds decisions
that the code states but cannot explain.

## Payment does not decide when a merchant is paid

An escrow hold is released only when order asks (`ReleaseEscrow`). Order knows whether the parcel
arrived, whether the return window has closed and whether a return is open; payment knows none of it.
Until 2026-10-09 payment released every hold 48 hours after it was created, delivered or not, and
also exposed an HTTP release endpoint. Both are gone, and so is the `auto_release_at` column.

The same holds for the courier's fee. `SettleShippingFee` comes only from order, after matching reports the
delivery, and names the courier who made it. An admin HTTP endpoint that paid the fee to whichever
principal it was given was removed on 2026-10-10: a settlement cannot be re-pointed, so paying the wrong
person first left the real courier unpaid, and settling before delivery made a cancelled order's refund
impossible. A settlement that does not go through is retried by order, not overridden here.

A hold released before its courier was known keeps the fee in the `UNASSIGNED_DRIVER_SHIPPING_FEE`
suspense wallet and writes `escrow:shipping:{order}` naming that wallet. When the courier's settlement
arrives later, the payout from suspense is recorded as `escrow:shipping:{order}:paid`. Until 2026-10-10
it reused `escrow:shipping:{order}`, which is unique, so every such settlement was refused and the fee
stayed in suspense; order kept retrying it, and those retries go through once this is deployed. The
suspense wallet pays out only for an order whose `escrow:shipping:{order}` row names it: it is one
balance shared by every order, so paying a fee that was never kept there would spend another
courier's. Holds released before 2026-09-16 kept nothing there and are refused.

## Returned goods

`RefundGoods` moves part of the merchant's share back to the buyer while the hold is still `HELD`:
the merchant's pending balance goes down, the buyer's wallet goes up, and the hold remembers
`goods_refunded_amount`. A later release pays the merchant only what is left. A refund of the whole
order (`RefundEscrow`) returns what is still held — total less goods already refunded. The shipping
fee is never part of a goods refund.

## Wallet arithmetic fails loudly

Moving escrow out of a wallet that does not hold it throws (`EscrowAmount.leaving`), rather than
clamping at zero. A clamp turned a bookkeeping error into money that silently stopped existing.
A shipping fee settled to a driver after a driverless release is credited straight to available
(`DriverWallet.creditAvailable`), because that driver never had it pending.

## The liability snapshot, for the notary

`payment.v1.LiabilityService/StreamLiabilitySnapshot` (contracts `payment/v1/liability.proto`) streams
everything payment owes in one currency, for `kinetix-notary-service` to commit to and anchor on chain.
The notary never reads this database; this RPC is the whole interface. What it promises:

- **What is owed, and nothing twice.** Every wallet row, every balance it holds: customer `balance`,
  merchant and driver `available_balance` and `pending_escrow_balance`, and the suspense wallet's two
  balances under its `purpose`. Zero balances are included, so a holder can prove they were owed nothing.
  **Escrow holds are not liabilities of their own**: checkout moves the buyer's money into the merchant's
  and driver's (or suspense's) pending balances, so the `HELD` row and the pending balance are the same
  money. Counting both would double it. Total liabilities move only on a top-up; every escrow operation
  is a transfer between the balances listed here.
- **`customer_wallets.held_balance` stops the snapshot.** Nothing has ever written it (checked through
  the whole history on 2026-10-12); a non-zero value means a flow this snapshot does not know about, and
  leaving it out would understate what is owed. `CustomerWallet.liabilities()` refuses instead.
- **One instant.** The read is one `REPEATABLE READ`, read-only transaction, one statement per wallet
  table; the adapter checks `transaction_isolation` and `transaction_read_only` itself and refuses to
  run otherwise. Proven on 2026-10-12 by `escrowTrafficNeverChangesWhatASnapshotSeesOwed`: escrow traffic
  between three parties while snapshots run must never change what the three are owed. Under
  `READ COMMITTED` the same test failed **295 of 1024 snapshots** over 353 movements (money counted
  twice or not at all, in exact 150,000 / 130,000 / 20,000 steps); under `REPEATABLE READ`, none.
  `as_of` is `clock_timestamp()` read by the statement that takes the snapshot.
- **The stream is header, batches of 500, trailer.** The trailer carries the account count and the
  total. Anything short of a trailer followed by `OK` is not a snapshot; a reader must also check the
  count and the sum itself.
- **Exact minor units or nothing.** A balance or total that is not a whole number of minor units, or
  does not fit an int64, fails the snapshot with `FAILED_PRECONDITION LIABILITY_SNAPSHOT_REFUSED` rather
  than being rounded or wrapped. `UNAVAILABLE LIABILITY_SNAPSHOT_UNAVAILABLE` means the ledger could not
  be read and is retryable; `INVALID_ARGUMENT` is a currency that is not ISO 4217.
- **Flow control is a poll, not `setOnReadyHandler`.** grpc-java delivers `onReady` on the call's
  serializing executor, behind the handler that is blocked writing the stream, so the callback cannot
  fire until the handler returns. `GrpcLiabilitySnapshotStream` polls `isReady()` (transport state) with
  backoff, watches the call's `Context` for a hang-up (cancelled from outside the serializer), and gives
  up after 30 seconds without progress so a stalled reader cannot hold the transaction open. Postgres
  backs that with `statement_timeout` 120s and `idle_in_transaction_session_timeout` 60s.

### Every snapshot is recorded, for the notary's watch

Since notary step 8 (2026-10-14) each snapshot has a `snapshot_id`, sent in the header, and a `digest`,
sent in the trailer. Before the trailer goes out, payment writes `liability_snapshot_records`: id, `as_of`,
currency, count, total and digest. If the record cannot be written there is no trailer, so a snapshot the
notary can use always has one. `payment.v1.LiabilityRecordService/GetLiabilitySnapshotRecord` serves it to
the notary's watch, which checks the rows the notary forwards against payment's own record rather than
against anything the notary says. The watch is the only caller: `notary-watch@payment.v1.LiabilityRecordService`.

The digest is SHA-256 over `"kinetix.payment.liability-snapshot.v1" ‖ as_of seconds:u64 ‖ as_of
nanos:u32 ‖ currency ‖ count:u64 ‖ total:u64`, then every account sorted by kind code and then by the
holder's UTF-8 bytes, each as `kind:u8 ‖ holderLength:u8 ‖ holder ‖ amount:u64`, all big-endian, amounts
in the same minor units the stream sends (`MinorUnits`, shared by both). The order is canonical so a
reader can rebuild it from rows in any order; a holder longer than 255 bytes refuses the snapshot. The
known-answer vector in `LiabilitySnapshotDigestTest` was computed by an independent Python
implementation, and the notary's Go tests assert the same value.

### Callers are scoped per service

`GRPC_ALLOWED_CALLERS` takes `caller` (every service on this server) or `caller@package.Service`
(that one service), the syntax matching already uses. The notary is listed as
`notary@payment.v1.LiabilityService`: it can read what is owed and can never reach an escrow RPC.

`order` stays a bare entry for now, which also lets it read the snapshot. Scoping it to
`order@payment.v1.PaymentService,order@grpc.reflection.v1.ServerReflection` was run end to end on
2026-10-12 (escrow and the gates' reflection probes pass, the snapshot is refused), but a payment image
older than this change reads `order@…` as a service literally named that and would refuse order's escrow
calls. Scope order once no image without the `@` parser can be rolled back to.

## Spring Boot 4, and what was kept back

Boot 3.5's last release carries Spring Framework 6.2.19, which has two critical CVEs fixed only in
7.0.9, so payment is on Boot 4.1.1. Three things in that move are decisions rather than mechanics:

- **gRPC is Boot's own** (`spring-boot-starter-grpc-server`), replacing net.devh, which was written for
  Boot 3. mTLS is the `grpc-server` SSL bundle with `client-auth: require`.
- **Boot's gRPC security auto-configuration is excluded.** With a `JwtDecoder` in the context it puts
  an interceptor on every gRPC call that demands an identity token. gRPC callers are services, admitted
  by mTLS and `PeerAuthorizationInterceptor`; tokens are for people, over HTTP. Leaving it on makes
  every call from order `UNAUTHENTICATED` — that was run and seen. gRPC observations are excluded too:
  they register `grpc.server.*` meters beside the names the metric contract gives payment.
- **Liquibase stays on 4.33.0.** Liquibase 5, which Boot 4 manages, is licensed FSL-1.1-ALv2, not an
  open-source licence; 4.33.0 is Apache-2.0 and runs under Boot 4 (the migrate profile and the
  changelog diff were both run). The diff uses `liquibase-hibernate7` 5.0.4, which is Apache-2.0 and
  works with core 4.33.0. Moving to Liquibase 5 is a licence decision for the user, not an upgrade.

`modules/api/build.gradle` also pins Tomcat 11.0.26 and Jackson 3.1.7 over Boot 4.1.1's 11.0.24 and 3.1.5,
which carried three critical and five high CVEs on 2026-10-10. Drop each pin once a Boot release manages
that version or later; leaving one behind makes this service lag every later fix.

## An unknown signing key is looked for at most twice every ten seconds

`SecurityConfig` builds its decoder on `RateLimitedKeys`, a Nimbus `JWKSourceBuilder` source rate-limited to
`UNKNOWN_KEY_REFRESH` (ten seconds; Nimbus allows two fetches per interval), not on `withJwkSetUri`, because
Spring Security 7 builds that source with `rateLimited(false)`. Until 2026-10-19 every token with an unknown
`kid` fetched identity's JWKS, so forged tokens with made-up key ids made identity answer once per request.
When the limit is reached `RateLimitedKeys` answers "no key", which Spring reports as a bad token (`401`);
Nimbus's own `RateLimitReachedException` would have surfaced as a decoding error, a `500`.
