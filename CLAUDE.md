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
