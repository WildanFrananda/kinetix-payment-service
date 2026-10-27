package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.AttentionReason;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionStatus;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionType;
import com.kinetix.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.kinetix.payment.domain.exception.GatewayRefusedException;
import com.kinetix.payment.domain.exception.GatewayUnavailableException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.InvalidArgumentException;
import com.kinetix.payment.domain.exception.TopUpLimitExceededException;
import com.kinetix.payment.domain.exception.TopUpNotFoundException;
import com.kinetix.payment.domain.gateway.GatewayCharge;
import com.kinetix.payment.domain.gateway.GatewayChargeRequest;
import com.kinetix.payment.domain.gateway.GatewaySettlement;
import com.kinetix.payment.domain.gateway.GatewayStatus;
import com.kinetix.payment.domain.gateway.PaymentInstructions;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import com.kinetix.payment.domain.port.PaymentTransactionRepositoryPort;
import com.kinetix.payment.domain.port.TopUpAlarmPort;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopUpService {
    private static final Logger LOG = LoggerFactory.getLogger(TopUpService.class);

    static final BigDecimal MINIMUM_AMOUNT = new BigDecimal("1000");

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static final String REFERENCE_PREFIX = "TOPUP-";

    private final PaymentTransactionRepositoryPort transactions;
    private final CustomerWalletRepositoryPort customerWallets;
    private final PaymentGatewayPort gateway;
    private final TransactionRunnerPort transactionRunner;
    private final AdvisoryLockPort advisoryLock;
    private final TopUpLimits limits;
    private final TopUpAlarmPort alarm;

    public TopUpService(
        PaymentTransactionRepositoryPort transactions,
        CustomerWalletRepositoryPort customerWallets,
        PaymentGatewayPort gateway,
        TransactionRunnerPort transactionRunner,
        AdvisoryLockPort advisoryLock,
        TopUpLimits limits,
        TopUpAlarmPort alarm
    ) {
        this.transactions = transactions;
        this.customerWallets = customerWallets;
        this.gateway = gateway;
        this.transactionRunner = transactionRunner;
        this.advisoryLock = advisoryLock;
        this.limits = limits;
        this.alarm = alarm;
    }

    public TopUpOutcome requestTopUp(TopUpCommand command) {
        long grossAmount = validated(command);

        Optional<PaymentTransaction> seen = transactions.findByPrincipalIdAndIdempotencyKey(
            command.customerPrincipalId(),
            command.idempotencyKey()
        );
        if (seen.isPresent()) {
            return replay(seen.get(), command);
        }

        PaymentTransaction pending = PaymentTransaction.pendingTopUp(
            REFERENCE_PREFIX + UUID.randomUUID(),
            command.customerPrincipalId(),
            command.idempotencyKey(),
            command.method(),
            command.bank(),
            command.amount()
        );
        PaymentTransaction recorded;
        try {
            recorded = recordIfTheWalletHasRoom(pending);
        } catch (DuplicateIdempotencyKeyException raced) {
            return replay(
                transactions.findByPrincipalIdAndIdempotencyKey(
                    command.customerPrincipalId(),
                    command.idempotencyKey()
                ).orElseThrow(() -> raced),
                command
            );
        }

        if (!recorded.referenceNumber().equals(pending.referenceNumber())) {
            return replay(recorded, command);
        }

        GatewayCharge charge = gateway.charge(
            new GatewayChargeRequest(
                recorded.referenceNumber(),
                grossAmount,
                command.method(),
                command.bank()
            )
        );
        return conclude(recorded, charge);
    }

    public TopUpOutcome findTopUp(String customerPrincipalId, String referenceNumber) {
        PaymentTransaction found = transactions.findByReferenceNumber(referenceNumber)
            .filter(transaction -> transaction.type() == TransactionType.TOPUP)
            .filter(transaction -> transaction.principalId().equals(customerPrincipalId))
            .orElseThrow(
                () -> new TopUpNotFoundException(
                    "no top-up " + referenceNumber + " belongs to this account"
                )
            );
        return new TopUpOutcome(found, instructionsFor(found), true);
    }

    public int reconcilePendingTopUps(Duration olderThan, int batchSize, Duration notFoundGrace) {
        Instant now = Instant.now();
        List<PaymentTransaction> stale = transactions.findPendingTopUpsOlderThan(now.minus(olderThan), batchSize);
        if (stale.isEmpty()) {
            return 0;
        }

        LOG.info("reconciling {} top-up(s) that have been pending longer than {}", stale.size(), olderThan);

        Optional<Instant> giveUpOnChargesCreatedBefore = Optional.of(now.minus(notFoundGrace));
        int concluded = 0;
        for (PaymentTransaction pending : stale) {
            String reference = pending.referenceNumber();
            try {
                SettlementOutcome outcome = settle(reference, giveUpOnChargesCreatedBefore);
                if (outcome.transaction().status() != TransactionStatus.PENDING) {
                    concluded++;
                    LOG.info("reconciled {}: {}", reference, outcome.transaction().status());
                }
            } catch (GatewayUnavailableException unavailable) {
                LOG.warn(
                    "reconciliation could not reach the gateway for {}; it stays pending and will "
                        + "be asked about again",
                    reference
                );
            } catch (RuntimeException failure) {
                LOG.error(
                    "reconciliation of {} failed and needs a person to look: {}",
                    reference,
                    failure.getMessage()
                );
            }
        }

        return concluded;
    }

    public SettlementOutcome settle(String referenceNumber) {
        return settle(referenceNumber, Optional.empty());
    }

    private SettlementOutcome settle(String referenceNumber, Optional<Instant> giveUpOnChargesCreatedBefore) {
        PaymentTransaction known = transactions.findByReferenceNumber(referenceNumber)
            .filter(transaction -> transaction.type() == TransactionType.TOPUP)
            .orElseThrow(() -> new TopUpNotFoundException("no top-up is recorded under " + referenceNumber));
        if (known.status() != TransactionStatus.PENDING) {
            return new SettlementOutcome(known, false);
        }

        GatewayStatus truth = gateway.statusOf(referenceNumber);
        Instant askedAt = Instant.now();
        if (truth.settlement() == GatewaySettlement.UNKNOWN) {
            transactionRunner.inNewTransaction(() -> {
                transactions.markChecked(referenceNumber, askedAt);
                return null;
            });

            LOG.warn(
                "top-up {} could not be confirmed with the payment gateway and stays pending: {}",
                referenceNumber,
                truth.detail()
            );
            throw new GatewayUnavailableException(
                referenceNumber,
                "the payment gateway could not be asked whether " + referenceNumber
                    + " was paid, so nothing was decided"
            );
        }

        return transactionRunner.inNewTransaction(() -> {
            advisoryLock.lockWalletOwner(known.principalId());
            PaymentTransaction current = lockedTopUp(referenceNumber);
            if (current.status() != TransactionStatus.PENDING) {
                return new SettlementOutcome(current, false);
            }

            transactions.markChecked(referenceNumber, askedAt);
            return switch (truth.settlement()) {
                case PENDING -> new SettlementOutcome(current, false);
                case SETTLED -> credit(current, truth);
                case FAILED -> new SettlementOutcome(
                    transactions.save(
                        current.concludedAs(
                            TransactionStatus.FAILED,
                            truth.externalTransactionId(),
                            truth.gatewayResponse()
                        )
                    ),
                    false
                );
                case EXPIRED -> new SettlementOutcome(
                    transactions.save(
                        current.concludedAs(
                            TransactionStatus.EXPIRED,
                            truth.externalTransactionId(),
                            truth.gatewayResponse()
                        )
                    ),
                    false
                );
                case NOT_FOUND -> unknownToTheGateway(current, truth, giveUpOnChargesCreatedBefore);
                case UNKNOWN -> throw new IllegalStateException("an unknown settlement was handled above");
            };
        });
    }

    private SettlementOutcome unknownToTheGateway(
        PaymentTransaction pending,
        GatewayStatus truth,
        Optional<Instant> giveUpOnChargesCreatedBefore
    ) {
        boolean pastGrace = giveUpOnChargesCreatedBefore
            .map(deadline -> pending.createdAt().isBefore(deadline))
            .orElse(false);
        if (!pastGrace) {
            LOG.warn(
                "the payment gateway has no transaction under {} yet; it stays pending and is asked again",
                pending.referenceNumber()
            );
            return new SettlementOutcome(pending, false);
        }

        if (pending.hasGatewayAnswer()) {
            return setAside(
                pending,
                AttentionReason.GATEWAY_HAS_NO_RECORD,
                truth,
                "the payment gateway accepted this charge and now has no transaction under it"
            );
        }

        LOG.warn(
            "top-up {} got no answer when it was charged and the payment gateway still has no transaction "
                + "under it; it is concluded as failed and nothing was credited",
            pending.referenceNumber()
        );
        return new SettlementOutcome(
            transactions.save(pending.concludedAs(TransactionStatus.FAILED, null, truth.gatewayResponse())),
            false
        );
    }

    private SettlementOutcome credit(PaymentTransaction pending, GatewayStatus truth) {
        if (truth.grossAmount() == null || truth.grossAmount().compareTo(pending.amount()) != 0) {
            return setAside(
                pending,
                AttentionReason.AMOUNT_MISMATCH,
                truth,
                "it was charged at " + pending.amount() + " but the payment gateway settled " + truth.grossAmount()
            );
        }

        CustomerWallet wallet = customerWallets.findByCustomerPrincipalIdForUpdate(pending.principalId())
            .orElseGet(() -> CustomerWallet.createInitial(pending.principalId()));
        customerWallets.save(wallet.topUp(pending.amount()));

        PaymentTransaction settled = transactions.save(
            pending.concludedAs(
                TransactionStatus.SUCCESS,
                truth.externalTransactionId(),
                truth.gatewayResponse()
            )
        );
        LOG.info("top-up {} settled; credited {}", settled.referenceNumber(), settled.amount());
        return new SettlementOutcome(settled, true);
    }

    private SettlementOutcome setAside(
        PaymentTransaction pending,
        AttentionReason reason,
        GatewayStatus truth,
        String detail
    ) {
        PaymentTransaction setAside = transactions.save(
            pending.setAsideFor(reason, truth.externalTransactionId(), truth.gatewayResponse())
        );
        alarm.setAside(setAside);
        LOG.error(
            "top-up {} was set aside as {} ({}): {}; nothing was credited and it needs a person to resolve it",
            setAside.referenceNumber(),
            TransactionStatus.NEEDS_ATTENTION,
            reason,
            detail
        );
        return new SettlementOutcome(setAside, false);
    }

    private PaymentTransaction recordIfTheWalletHasRoom(PaymentTransaction pending) {
        return transactionRunner.inNewTransaction(() -> {
            advisoryLock.lockWalletOwner(pending.principalId());
            Optional<PaymentTransaction> earlier = transactions.findByPrincipalIdAndIdempotencyKey(
                pending.principalId(),
                pending.idempotencyKey()
            );
            if (earlier.isPresent()) {
                return earlier.get();
            }

            requireRoomInTheWallet(pending);
            return transactions.save(pending);
        });
    }

    private TopUpOutcome conclude(PaymentTransaction pending, GatewayCharge charge) {
        switch (charge.outcome()) {
            case ACCEPTED -> {
                PaymentTransaction recorded = transactionRunner.inNewTransaction(() -> {
                    PaymentTransaction current = lockedTopUp(pending.referenceNumber());
                    if (current.status() != TransactionStatus.PENDING) {
                        return current;
                    }

                    return transactions.save(
                        current.withGatewayResponse(charge.externalTransactionId(), charge.gatewayResponse())
                    );
                });

                PaymentInstructions instructions = recorded.status() == TransactionStatus.PENDING
                    ? charge.instructions()
                    : PaymentInstructions.none();
                return new TopUpOutcome(recorded, instructions, false);
            }
            case REFUSED -> {
                transactionRunner.inNewTransaction(() -> {
                    PaymentTransaction current = lockedTopUp(pending.referenceNumber());
                    if (current.status() != TransactionStatus.PENDING) {
                        return current;
                    }

                    return transactions.save(
                        current.concludedAs(TransactionStatus.FAILED, null, charge.gatewayResponse())
                    );
                });

                LOG.warn(
                    "top-up {} was refused by the payment gateway: {}",
                    pending.referenceNumber(),
                    charge.detail()
                );
                throw new GatewayRefusedException(
                    pending.referenceNumber(),
                    "the payment gateway refused this top-up; no charge was created and no balance changed"
                );
            }
            default -> {
                LOG.error(
                    "top-up {} got no definite answer from the payment gateway and stays pending; "
                        + "the charge may exist and a notification may still settle it: {}",
                    pending.referenceNumber(),
                    charge.detail()
                );
                throw new GatewayUnavailableException(
                    pending.referenceNumber(),
                    "the payment gateway gave no definite answer; this top-up stays pending, so ask for its "
                        + "status rather than starting another"
                );
            }
        }
    }

    private PaymentTransaction lockedTopUp(String referenceNumber) {
        return transactions.findByReferenceNumberForUpdate(referenceNumber)
            .orElseThrow(() -> new TopUpNotFoundException("no top-up is recorded under " + referenceNumber));
    }

    private TopUpOutcome replay(PaymentTransaction existing, TopUpCommand command) {
        boolean sameRequest = existing.type() == TransactionType.TOPUP
            && existing.method() == command.method()
            && existing.bank() == command.bank()
            && existing.amount().compareTo(command.amount()) == 0;
        if (!sameRequest) {
            throw new IdempotencyConflictException(
                "this Idempotency-Key was already used for a different top-up; a key names one request"
            );
        }

        if (existing.status() == TransactionStatus.PENDING && !existing.hasGatewayAnswer()) {
            throw new GatewayUnavailableException(
                existing.referenceNumber(),
                "an earlier attempt with this key got no definite answer from the payment gateway; ask for "
                    + "this top-up's status rather than retrying"
            );
        }

        if (existing.externalTransactionId() == null && existing.status() == TransactionStatus.FAILED) {
            throw new GatewayRefusedException(
                existing.referenceNumber(),
                "the payment gateway refused this top-up; no charge was created and no balance changed"
            );
        }

        return new TopUpOutcome(existing, instructionsFor(existing), true);
    }

    private PaymentInstructions instructionsFor(PaymentTransaction transaction) {
        if (transaction.status() != TransactionStatus.PENDING || !transaction.hasGatewayAnswer()) {
            return PaymentInstructions.none();
        }

        return gateway.instructionsFrom(transaction.gatewayResponse());
    }

    private void requireRoomInTheWallet(PaymentTransaction pending) {
        BigDecimal balance = customerWallets.findByCustomerPrincipalId(pending.principalId())
            .map(CustomerWallet::balance)
            .orElse(BigDecimal.ZERO);
        BigDecimal awaitingPayment = transactions.pendingTopUpTotal(pending.principalId());
        BigDecimal balanceAfter = balance.add(awaitingPayment).add(pending.amount());
        if (balanceAfter.compareTo(limits.maximumCustomerBalance()) > 0) {
            throw new TopUpLimitExceededException(
                TopUpLimitExceededException.WALLET_BALANCE,
                limits.maximumCustomerBalance(),
                roomRefusal(balanceAfter, awaitingPayment)
            );
        }
    }

    private String roomRefusal(BigDecimal balanceAfter, BigDecimal awaitingPayment) {
        String limit = "above the IDR " + rupiah(limits.maximumCustomerBalance()) + " a customer wallet may hold";
        if (awaitingPayment.signum() == 0) {
            return "this top-up would bring the wallet to IDR " + rupiah(balanceAfter) + ", " + limit;
        }

        return "with IDR " + rupiah(awaitingPayment) + " in top-ups still awaiting payment, this top-up would bring "
            + "the wallet to IDR " + rupiah(balanceAfter) + ", " + limit + "; a top-up that expires or fails stops "
            + "counting";
    }

    private long validated(TopUpCommand command) {
        if (command.customerPrincipalId() == null || command.customerPrincipalId().isBlank()) {
            throw new InvalidArgumentException("a customer principal id is required");
        }

        if (command.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(command.idempotencyKey()).matches()) {
            throw new InvalidArgumentException(
                "an Idempotency-Key of 1 to 128 characters from A-Z a-z 0-9 . _ : - is required"
            );
        }

        if (command.amount() == null || command.amount().compareTo(MINIMUM_AMOUNT) < 0) {
            throw new InvalidArgumentException("the minimum top-up is IDR 1,000");
        }

        if (command.amount().compareTo(limits.maximumTopUp()) > 0) {
            throw new TopUpLimitExceededException(
                TopUpLimitExceededException.TOP_UP_AMOUNT,
                limits.maximumTopUp(),
                "a single top-up is at most IDR " + rupiah(limits.maximumTopUp())
            );
        }

        BigDecimal whole = command.amount().stripTrailingZeros();
        if (whole.scale() > 0) {
            throw new InvalidArgumentException(
                "a rupiah top-up is a whole amount; "
                    + command.amount().toPlainString() + " has a fraction the gateway cannot charge"
            );
        }

        if (command.method() == PaymentMethod.MIDTRANS_VA) {
            if (command.bank() == null) {
                throw new InvalidArgumentException("a virtual-account top-up needs a bank");
            }
        } else if (command.method() == PaymentMethod.MIDTRANS_QRIS) {
            if (command.bank() != null) {
                throw new InvalidArgumentException("a QRIS top-up does not take a bank");
            }
        } else {
            throw new InvalidArgumentException("a top-up is paid by virtual account or QRIS");
        }

        return whole.longValueExact();
    }

    private static String rupiah(BigDecimal amount) {
        return new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }
}
