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
When the limit is reached `RateLimitedKeys` answers "no key", which Spring reports as a bad token (`401`),
but only while the last fetch succeeded. `ObservedKeySetSource` records whether the last fetch from identity
failed; if it did, a rate-limited lookup is "identity is down", not "this key is forged", and answers
unavailable. Until 2026-10-21 an identity outage read as `401` for every request after the first two.

## HTTP failures keep their meaning

Proven on 2026-10-21 by `HttpFailuresThroughTheFilterChainTest` and `IdentityOutageThroughTheFilterChainTest`,
which run the real filter chain on a real Tomcat (`HttpFailureProbeApplication`; MockMvc never makes the
container's `ERROR` dispatch, so it cannot see this). Before the fix the anonymous Midtrans webhook answered
an **empty 401** for a body that is not JSON, a database outage and any unexpected failure, and every request
answered an empty 401 while identity's JWKS was unreachable: Spring renders those through `/error`, and
`/error` required a token. What holds now:

- `SecurityConfig` permits the `ERROR` dispatch, so a failure reaches the client with its own status.
- `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, so every Spring MVC exception (missing
  header, unreadable body, failed validation, 405, 415, …) answers its status in the `{"error": …}` shape.
- A ledger outage (`CannotCreateTransactionException`, `DataAccessResourceFailureException`, transient and
  recoverable data-access failures, a transaction timeout) is `503`. Other data-access errors stay `500`: a
  constraint the code did not expect is a defect, not an outage.
- `AuthenticationRefusals` writes `401` for a missing or bad token and `503` when the token could not be
  checked (`AuthenticationServiceException`, which Spring raises for a JWKS outage and would otherwise rethrow
  as a `500`).
- Its challenge is `WWW-Authenticate: Bearer`, or `Bearer error="…"` with the RFC 6750 code, and nothing else.
  Spring Security 7.1's `BearerTokenAuthenticationEntryPoint` always adds `resource_metadata` built from the
  request's own URL (a resolver returning null prints `"null"`), and Kong does not preserve the host, so every
  401 named `http://kinetix-payment-service:8003`; its `error_description` was exception text. Seen and fixed
  on 2026-10-22 (`…ForwardedByKongIsChallengedWithoutNamingPaymentsInternalHost`).
- Payment does not serve `/.well-known/oauth-protected-resource` (2026-10-27). Spring Security 7.1 adds that
  endpoint to every resource server with no switch to turn it off, and it advertised the request's own host and
  certificate-bound tokens, which payment does not use; `SecurityConfig` drops `OAuth2ProtectedResourceMetadataFilter`
  from the built chain. The path now answers like any unknown one: `401` without a token, `404` with one.
- The `401` and `503` bodies `AuthenticationRefusals` writes are UTF-8 bytes under `Content-Type: application/json`,
  like every Spring MVC answer. Until 2026-10-27 they went through `getWriter()`, which made Tomcat add
  `;charset=ISO-8859-1`.
- `ErrorResponseController` replaces Boot's `/error` page, so whatever still reaches the container (an
  exception nothing mapped, a `sendError` from a filter) answers its status as `{"error": …}`, and a `500`
  never carries the exception's text.
- Hikari waits at most 3 s for a connection (`spring.datasource.hikari.connection-timeout`). Its default,
  30 s, held every request for half a minute before the `503` during an outage; 3 s was measured with
  Postgres stopped under the running jar on 2026-10-21.

`JsonConfiguration` holds the one `JsonMapper` on its own so that probe context can load the production mapper
without the service beans.

## Top-up limits, and top-ups set aside for a person

The user ruled on 2026-10-21: a customer top-up is at most IDR 2,000,000 and a customer wallet holds at most
IDR 2,000,000 (`TOP_UP_MAXIMUM_AMOUNT`, `CUSTOMER_WALLET_MAXIMUM_BALANCE`, required, checked by `TopUpLimits`
at start). Both are checked before Midtrans is called and refused with `422` and a `code`
(`TOP_UP_AMOUNT_ABOVE_LIMIT`, `WALLET_BALANCE_LIMIT_EXCEEDED`). A replay is never refused by them: its first
request already passed. They limit what enters from outside: merchant and driver wallets are not capped.

**A refund credits in full, above the limit too** (the user's ruling, 2026-10-22). `RefundEscrow` and
`RefundGoods` return the buyer's own money and never check the limit, so a customer wallet may hold more than
IDR 2,000,000 after one. The limit governs top-ups only: such a wallet takes no top-up until it is spent below
the limit.

**A paid top-up is always credited** (ruled 2026-10-27). The wallet limit is checked once, when the top-up is
created; a top-up Midtrans reports paid is credited in full at settlement even if the wallet then holds more than
the limit. Until 2026-10-27 settlement checked the limit again, so a top-up already pending when a refund landed
was paid at Midtrans and set aside `NEEDS_ATTENTION` / `BALANCE_LIMIT_EXCEEDED` instead of credited
(`aTopUpPaidAfterARefundFilledTheWalletIsCreditedInFull`).

The room a top-up needs is the balance **plus every top-up of that customer still `PENDING`** plus this one.
It is read under the wallet owner's advisory lock (`lockWalletOwner`, the lock settlement and escrow take), in
the transaction that inserts the new `PENDING` row, and the gateway is called only after that commits. So of
two top-ups started together whose sum crosses the limit, the second waits for the first's row and is refused
`422` before Midtrans is called (`ofTwoTopUpsStartedTogetherWhoseSumCrossesTheLimitOnlyOneReachesTheGateway`).
Until 2026-10-22 the check read the balance alone, so every top-up started before the first settled was
charged, and the ones that no longer fitted were paid and set aside. The key is looked up again under the
lock, so a retry that waited behind its own first attempt is replayed, not refused. A top-up stops counting
when it leaves `PENDING`: settled (its amount is then in the balance), `FAILED`, `EXPIRED` or
`NEEDS_ATTENTION`. An abandoned one holds its room until the gateway expires it and the notification or the
sweep records that; there is no cancel.

A settlement that cannot be credited is never left `PENDING`: it becomes
`NEEDS_ATTENTION` with an `attention_reason`, leaves the sweep (which reads only `PENDING`), logs an error and
counts `kinetix_payment_top_ups_needing_attention_total{reason}`. The reasons:

- `BALANCE_LIMIT_EXCEEDED`: written until 2026-10-27, when settlement stopped checking the limit. The value stays
  in `AttentionReason` because rows set aside before then carry it; nothing writes it any more.
- `AMOUNT_MISMATCH`: the gateway settled another amount than was charged.
- `GATEWAY_HAS_NO_RECORD`: the gateway accepted the charge and, after the grace, no longer knows it.

A charge that never got an answer and that the gateway still does not know after
`kinetix.reconciliation.not-found-grace` (1h in `application.yml`, counted from creation) never reached it,
and is concluded `FAILED`. Inside the grace it stays `PENDING`. Only the sweep concludes it; a notification never does.
Resolving a set-aside top-up (refund at Midtrans, or credit it) is a person's decision and has no endpoint yet:
`settle` returns early for anything that is not `PENDING`, so neither the sweep nor a repeated notification
touches it. It belongs to the planned backoffice, built here as an API when it is needed.

Before this the sweep took the oldest 100 `PENDING` rows and these rows stayed `PENDING`, throwing `409` on
every sweep, so 100 of them blocked every newer lost notification.

Top-ups the customer abandoned stay `PENDING` at the gateway until it expires them, so they could hold the
same window. Every time the gateway is asked about a top-up, `last_checked_at` is set (an update of that
column alone, never through `save()`, which would write a stale copy over it), and the sweep takes rows never
asked first, then the least recently asked. `aLostNotificationIsReachedWhileOlderAbandonedTopUpsStillFillTheBatch`
fails with the old `ORDER BY created_at`.

## No exception text crosses the wire

Since 2026-10-27 a gRPC status description, its `ErrorDetail` trailer and an HTTP `error` body are either a sentence
payment wrote for the caller or a fixed sentence; the exception goes to the log, where the request id already is
(`RequestIdFilter` for HTTP, `PeerAuthorizationInterceptor` for gRPC). Until then `GrpcRefusal` copied any
exception's message, so a database failure answered `ABORTED ESCROW_CONTENTION` with JDBC and SQL text, and the
liability snapshot's refusal carried the JDK's arithmetic message. `GrpcRefusal.of` takes a string, not a
`Throwable`, so each call site names its text.

- Payment's own refusals are `DomainException` and its subclasses, and `InvalidArgumentException` for an argument
  check. Every `IllegalArgumentException` payment throws is an `InvalidArgumentException`; a bare one at the boundary
  came from the JDK or a library, answers `INVALID_ARGUMENT` / `400` with "the request is not valid", and is logged.
- Ledger failures answer fixed sentences: `ABORTED ESCROW_CONTENTION` "the escrow ledger could not complete this
  call; retry it", `UNAVAILABLE LIABILITY_SNAPSHOT_UNAVAILABLE` and `LIABILITY_SNAPSHOT_RECORD_UNAVAILABLE`.
  `LIABILITY_SNAPSHOT_REFUSED` keeps its cause for the log and says only that the ledger could not be reported
  exactly.

## The charge reply is written under the row's lock

`conclude()` re-reads the top-up `FOR UPDATE` and writes the gateway's reply only while it is still
`PENDING`. It used to save its in-memory copy, which set a top-up the notification had settled meanwhile back
to `PENDING`; the next sweep then credited it again. `TopUpLedgerIntegrationTest` proved it on Postgres: one
paid top-up of 150,000 left the wallet holding 300,000.

A replay compares type, method, bank and amount. Whether the gateway answered is `gateway_response` being set,
not `external_transaction_id`: an accepted charge with no transaction id used to answer every retry `503`.

## A balance read opens nothing

`GET …/wallet/{customer,merchant,driver}/balance` reads and never writes. An absent wallet answers zero
balances with `"exists": false`; a wallet is opened by the first money that reaches it (a settled top-up or an
escrow movement), which is how order's escrow calls already treat an absent wallet. A principal with no wallet
is therefore not in a liability snapshot until money reaches it. Admins hold no wallet: a token whose role is
not the wallet's kind is `403`, so an admin can neither open a wallet nor top up under its own principal. No
endpoint reads another principal's wallet; that is the backoffice's, when it exists.

## The JDK blocks eMudhra's compromised intermediates (CVE-2026-91403)

eMudhra's intermediate CA keys were compromised in October 2026, and every JDK up to 21.0.13 still trusted the 16
intermediates (CVE-2026-91403, CVSS 9.1; Oracle's and OpenJDK's alerts of 2026-10-21). The fixed builds are 21.0.13.0.2
and later, which Temurin had not released on 2026-10-23. `security/jdk21-blocked.certs` is the `blocked.certs` that fix
ships: OpenJDK's own converter (`BlockedCertsConverter.java`) run on `blocked.certs.pem`, both from openjdk/jdk21u at
`85974eed` (8395142, "CA Enhancements"). It reproduces the 38 fingerprints this image's JDK already carried and adds 48,
because the converter blocks every equivalent encoding of a certificate (two for RSA, four for ECDSA), not only the one
fingerprint a blog lists. Updating Ubuntu's `ca-certificates` does nothing for it: Temurin reads its own files.

Both stages of the Dockerfile install it over the JDK's file and refuse to build when the JDK already blocks something
the file does not: that is a newer JDK, and the override must then be deleted, not left to shrink its blocklist. Measured
on 2026-10-23 against a test server whose chain runs through `EM DV TLS CA - G3B`: the image before the override
connected (HTTP 200), the image with it refused (`Untrusted certificate`), and Midtrans, FCM, Google OAuth and Resend still
connected. Delete the file and both steps when the base image's JDK is 21.0.13.0.2 or later.
