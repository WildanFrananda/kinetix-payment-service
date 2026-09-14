package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionStatus;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionType;
import com.kinetix.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.kinetix.payment.domain.exception.GatewayRefusedException;
import com.kinetix.payment.domain.exception.GatewayUnavailableException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.SettlementMismatchException;
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
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopUpService {
    private static final Logger LOG = LoggerFactory.getLogger(TopUpService.class);

    static final BigDecimal MINIMUM_AMOUNT = new BigDecimal("1000");

    private static final BigDecimal MAXIMUM_STORABLE_AMOUNT = new BigDecimal("9999999999");

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static final String REFERENCE_PREFIX = "TOPUP-";

    private final PaymentTransactionRepositoryPort transactions;
    private final CustomerWalletRepositoryPort customerWallets;
    private final PaymentGatewayPort gateway;
    private final TransactionRunnerPort transactionRunner;
    private final AdvisoryLockPort advisoryLock;

    public TopUpService(
        PaymentTransactionRepositoryPort transactions,
        CustomerWalletRepositoryPort customerWallets,
        PaymentGatewayPort gateway,
        TransactionRunnerPort transactionRunner,
        AdvisoryLockPort advisoryLock
    ) {
        this.transactions = transactions;
        this.customerWallets = customerWallets;
        this.gateway = gateway;
        this.transactionRunner = transactionRunner;
        this.advisoryLock = advisoryLock;
    }

    public TopUpOutcome requestTopUp(TopUpCommand command) {
        long grossAmount = validated(command);

        Optional<PaymentTransaction> seen = transactions.findByPrincipalIdAndIdempotencyKey(
            command.customerPrincipalId(), command.idempotencyKey()
        );
        if (seen.isPresent()) {
            return replay(seen.get(), command);
        }

        PaymentTransaction pending;
        try {
            pending = recordPending(command);
        } catch (DuplicateIdempotencyKeyException raced) {
            return replay(
                transactions.findByPrincipalIdAndIdempotencyKey(
                    command.customerPrincipalId(), command.idempotencyKey()
                ).orElseThrow(() -> raced),
                command
            );
        }

        GatewayCharge charge = gateway.charge(new GatewayChargeRequest(
            pending.referenceNumber(), grossAmount, command.method(), command.bank()
        ));
        return conclude(pending, charge);
    }

    public TopUpOutcome findTopUp(String customerPrincipalId, String referenceNumber) {
        PaymentTransaction found = transactions.findByReferenceNumber(referenceNumber)
            .filter(transaction -> transaction.type() == TransactionType.TOPUP)
            .filter(transaction -> transaction.principalId().equals(customerPrincipalId))
            .orElseThrow(() -> new TopUpNotFoundException(
                "no top-up " + referenceNumber + " belongs to this account"
            ));
        return new TopUpOutcome(found, instructionsFor(found), true);
    }

    public SettlementOutcome settle(String referenceNumber) {
        PaymentTransaction known = transactions.findByReferenceNumber(referenceNumber)
            .filter(transaction -> transaction.type() == TransactionType.TOPUP)
            .orElseThrow(() -> new TopUpNotFoundException("no top-up is recorded under " + referenceNumber));
        if (known.status() != TransactionStatus.PENDING) {
            return new SettlementOutcome(known, false);
        }

        GatewayStatus truth = gateway.statusOf(referenceNumber);
        if (truth.settlement() == GatewaySettlement.UNKNOWN) {
            LOG.warn("top-up {} could not be confirmed with the payment gateway and stays pending: {}",
                referenceNumber, truth.detail()
            );
            throw new GatewayUnavailableException(referenceNumber,
                "the payment gateway could not be asked whether " + referenceNumber
                    + " was paid, so nothing was decided"
            );
        }

        return transactionRunner.inNewTransaction(() -> {
            advisoryLock.lockWalletOwner(known.principalId());
            PaymentTransaction current = transactions.findByReferenceNumberForUpdate(referenceNumber)
                .orElseThrow(() -> new TopUpNotFoundException("no top-up is recorded under " + referenceNumber));
            if (current.status() != TransactionStatus.PENDING) {
                return new SettlementOutcome(current, false);
            }

            return switch (truth.settlement()) {
                case PENDING -> new SettlementOutcome(current, false);
                case SETTLED -> credit(current, truth);
                case FAILED -> new SettlementOutcome(transactions.save(current.concludedAs(
                    TransactionStatus.FAILED, truth.externalTransactionId(), truth.gatewayResponse()
                )), false);
                case EXPIRED -> new SettlementOutcome(transactions.save(current.concludedAs(
                    TransactionStatus.EXPIRED, truth.externalTransactionId(), truth.gatewayResponse()
                )), false);
                case NOT_FOUND -> {
                    LOG.error("top-up {} was recorded as charged, but the payment gateway has no such "
                        + "transaction; nothing was credited and it needs a person to look", referenceNumber
                    );
                    throw new SettlementMismatchException(
                        "the payment gateway has no transaction under " + referenceNumber
                    );
                }
                case UNKNOWN -> throw new IllegalStateException("an unknown settlement was handled above");
            };
        });
    }

    private SettlementOutcome credit(PaymentTransaction pending, GatewayStatus truth) {
        if (truth.grossAmount() == null || truth.grossAmount().compareTo(pending.amount()) != 0) {
            LOG.error("top-up {} was charged at {} but the payment gateway settled {}; nothing was "
                + "credited and it needs a person to look", pending.referenceNumber(), pending.amount(),
                truth.grossAmount()
            );
            throw new SettlementMismatchException("the payment gateway settled " + truth.grossAmount()
                + " for " + pending.referenceNumber() + ", which was charged at " + pending.amount()
                + "; nothing was credited"
            );
        }

        CustomerWallet wallet = customerWallets.findByCustomerPrincipalIdForUpdate(pending.principalId())
            .orElseGet(() -> CustomerWallet.createInitial(pending.principalId()));
        customerWallets.save(wallet.topUp(pending.amount()));

        PaymentTransaction settled = transactions.save(pending.concludedAs(
            TransactionStatus.SUCCESS, truth.externalTransactionId(), truth.gatewayResponse()
        ));
        LOG.info("top-up {} settled; credited {}", settled.referenceNumber(), settled.amount());
        return new SettlementOutcome(settled, true);
    }

    private PaymentTransaction recordPending(TopUpCommand command) {
        PaymentTransaction pending = PaymentTransaction.pendingTopUp(
            REFERENCE_PREFIX + UUID.randomUUID(),
            command.customerPrincipalId(),
            command.idempotencyKey(),
            command.method(),
            command.amount()
        );
        return transactionRunner.inNewTransaction(() -> transactions.save(pending));
    }

    private TopUpOutcome conclude(PaymentTransaction pending, GatewayCharge charge) {
        switch (charge.outcome()) {
            case ACCEPTED -> {
                PaymentTransaction accepted = transactionRunner.inNewTransaction(() -> transactions.save(
                    pending.withGatewayResponse(charge.externalTransactionId(), charge.gatewayResponse())
                ));
                return new TopUpOutcome(accepted, charge.instructions(), false);
            }
            case REFUSED -> {
                transactionRunner.inNewTransaction(() -> transactions.save(
                    pending.concludedAs(TransactionStatus.FAILED, null, charge.gatewayResponse())
                ));
                LOG.warn("top-up {} was refused by the payment gateway: {}", pending.referenceNumber(),
                    charge.detail()
                );
                throw new GatewayRefusedException(pending.referenceNumber(),
                    "the payment gateway refused this top-up; no charge was created and no balance changed"
                );
            }
            default -> {
                LOG.error("top-up {} got no definite answer from the payment gateway and stays pending; "
                    + "the charge may exist and a notification may still settle it: {}",
                    pending.referenceNumber(), charge.detail()
                );
                throw new GatewayUnavailableException(pending.referenceNumber(),
                    "the payment gateway gave no definite answer; this top-up stays pending, so ask for its "
                        + "status rather than starting another"
                );
            }
        }
    }

    private TopUpOutcome replay(PaymentTransaction existing, TopUpCommand command) {
        boolean sameRequest = existing.type() == TransactionType.TOPUP
            && existing.method() == command.method()
            && existing.amount().compareTo(command.amount()) == 0;
        if (!sameRequest) {
            throw new IdempotencyConflictException(
                "this Idempotency-Key was already used for a different top-up; a key names one request"
            );
        }

        if (existing.externalTransactionId() == null && existing.status() == TransactionStatus.PENDING) {
            throw new GatewayUnavailableException(existing.referenceNumber(),
                "an earlier attempt with this key got no definite answer from the payment gateway; ask for "
                    + "this top-up's status rather than retrying"
            );
        }
        if (existing.externalTransactionId() == null && existing.status() == TransactionStatus.FAILED) {
            throw new GatewayRefusedException(existing.referenceNumber(),
                "the payment gateway refused this top-up; no charge was created and no balance changed"
            );
        }
        return new TopUpOutcome(existing, instructionsFor(existing), true);
    }

    private PaymentInstructions instructionsFor(PaymentTransaction transaction) {
        if (transaction.status() != TransactionStatus.PENDING || transaction.gatewayResponse() == null) {
            return PaymentInstructions.none();
        }
        return gateway.instructionsFrom(transaction.gatewayResponse());
    }

    private static long validated(TopUpCommand command) {
        if (command.customerPrincipalId() == null || command.customerPrincipalId().isBlank()) {
            throw new IllegalArgumentException("a customer principal id is required");
        }
        if (command.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(command.idempotencyKey()).matches()) {
            throw new IllegalArgumentException(
                "an Idempotency-Key of 1 to 128 characters from A-Z a-z 0-9 . _ : - is required"
            );
        }
        if (command.amount() == null || command.amount().compareTo(MINIMUM_AMOUNT) < 0) {
            throw new IllegalArgumentException("the minimum top-up is IDR 1,000");
        }
        if (command.amount().compareTo(MAXIMUM_STORABLE_AMOUNT) > 0) {
            throw new IllegalArgumentException("a single top-up cannot exceed IDR 9,999,999,999");
        }
        BigDecimal whole = command.amount().stripTrailingZeros();
        if (whole.scale() > 0) {
            throw new IllegalArgumentException("a rupiah top-up is a whole amount; "
                + command.amount().toPlainString() + " has a fraction the gateway cannot charge"
            );
        }

        if (command.method() == PaymentMethod.MIDTRANS_VA) {
            if (command.bank() == null) {
                throw new IllegalArgumentException("a virtual-account top-up needs a bank");
            }
        } else if (command.method() == PaymentMethod.MIDTRANS_QRIS) {
            if (command.bank() != null) {
                throw new IllegalArgumentException("a QRIS top-up does not take a bank");
            }
        } else {
            throw new IllegalArgumentException("a top-up is paid by virtual account or QRIS");
        }
        return whole.longValueExact();
    }
}
