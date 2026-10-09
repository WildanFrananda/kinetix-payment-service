# CLAUDE.md — kinetix-payment-service

The workspace `AGENTS.md` holds the stack and the verification commands. This file holds decisions
that the code states but cannot explain.

## Payment does not decide when a merchant is paid

An escrow hold is released only when order asks (`ReleaseEscrow`). Order knows whether the parcel
arrived, whether the return window has closed and whether a return is open; payment knows none of it.
Until 2026-10-09 payment released every hold 48 hours after it was created, delivered or not, and
also exposed an HTTP release endpoint. Both are gone, and so is the `auto_release_at` column.

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
