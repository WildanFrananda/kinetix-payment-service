package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.entity.EscrowIdempotencyRecord;
import com.kinetix.payment.domain.entity.EscrowOperation;
import com.kinetix.payment.domain.entity.IdempotencyKeySource;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.kinetix.payment.domain.exception.DuplicateOrderNumberException;
import com.kinetix.payment.domain.exception.EscrowNotFoundException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.OrderAlreadyUnwoundException;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.EscrowIdempotencyRepositoryPort;
import com.kinetix.payment.domain.port.EscrowRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import com.kinetix.payment.domain.port.PaymentTransactionRepositoryPort;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EscrowService {
    private static final Logger LOG = LoggerFactory.getLogger(EscrowService.class);

    private static final int MAX_CALLER_KEY_LENGTH = 128;

    private static final int MAX_DETAIL_LENGTH = 512;

    private final EscrowRepositoryPort escrowRepository;
    private final CustomerWalletRepositoryPort customerWalletRepository;
    private final MerchantWalletRepositoryPort merchantWalletRepository;
    private final DriverWalletRepositoryPort driverWalletRepository;
    private final PaymentTransactionRepositoryPort paymentTransactionRepository;
    private final EscrowIdempotencyRepositoryPort idempotencyRepository;
    private final TransactionRunnerPort transactionRunner;
    private final AdvisoryLockPort advisoryLock;

    public EscrowService(
        EscrowRepositoryPort escrowRepository,
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository,
        PaymentTransactionRepositoryPort paymentTransactionRepository,
        EscrowIdempotencyRepositoryPort idempotencyRepository,
        TransactionRunnerPort transactionRunner,
        AdvisoryLockPort advisoryLock
    ) {
        this.escrowRepository = escrowRepository;
        this.customerWalletRepository = customerWalletRepository;
        this.merchantWalletRepository = merchantWalletRepository;
        this.driverWalletRepository = driverWalletRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.transactionRunner = transactionRunner;
        this.advisoryLock = advisoryLock;
    }

    private <T> T inOrderTransaction(String orderNumber, Supplier<T> work) {
        return transactionRunner.inNewTransaction(() -> {
            advisoryLock.lockOrder(orderNumber);
            return work.get();
        });
    }

    public EscrowOutcome createEscrowHold(CreateEscrowHoldCommand command) {
        String key = resolveKey(command.idempotencyKey(), command.orderNumber());
        IdempotencyKeySource source =
            sourceOf(command.idempotencyKey(), EscrowOperation.CREATE_HOLD, command.orderNumber());

        Optional<EscrowIdempotencyRecord> seen =
            idempotencyRepository.find(EscrowOperation.CREATE_HOLD, key);
        if (seen.isPresent()) {
            return inOrderTransaction(command.orderNumber(),
                () -> replayCreateHold(seen.get(), command.requestFingerprint())
            );
        }

        try {
            return inOrderTransaction(command.orderNumber(),
                () -> applyCreateHold(command, key, source)
            );
        } catch (DuplicateIdempotencyKeyException duplicateKey) {
            return inOrderTransaction(command.orderNumber(), () -> replayCreateHold(
                idempotencyRepository.find(EscrowOperation.CREATE_HOLD, key)
                    .orElseThrow(() -> duplicateKey),
                command.requestFingerprint()
            ));
        } catch (DuplicateOrderNumberException duplicateOrder) {
            return recoverFromDuplicateOrderNumber(command, key, source);
        }
    }

    public EscrowOutcome releaseEscrow(String orderNumber) {
        return releaseEscrow(orderNumber, null);
    }

    public EscrowOutcome releaseEscrow(String orderNumber, String idempotencyKey) {
        String key = resolveKey(idempotencyKey, orderNumber);
        IdempotencyKeySource source = sourceOf(idempotencyKey, EscrowOperation.RELEASE, orderNumber);
        String fingerprint = EscrowRequestFingerprint.forRelease(orderNumber);

        Optional<EscrowIdempotencyRecord> seen =
            idempotencyRepository.find(EscrowOperation.RELEASE, key);
        if (seen.isPresent()) {
            return inOrderTransaction(orderNumber, () -> replaySettled(seen.get(), fingerprint));
        }

        try {
            return inOrderTransaction(orderNumber,
                () -> applyRelease(orderNumber, key, source, fingerprint)
            );
        } catch (DuplicateIdempotencyKeyException duplicateKey) {
            return inOrderTransaction(orderNumber, () -> replaySettled(
                idempotencyRepository.find(EscrowOperation.RELEASE, key)
                    .orElseThrow(() -> duplicateKey),
                fingerprint
            ));
        }
    }

    public EscrowOutcome refundEscrow(String orderNumber, String reason, String idempotencyKey) {
        String key = resolveKey(idempotencyKey, orderNumber);
        IdempotencyKeySource source = sourceOf(idempotencyKey, EscrowOperation.REFUND, orderNumber);
        String fingerprint = EscrowRequestFingerprint.forRefund(orderNumber);

        Optional<EscrowIdempotencyRecord> seen =
            idempotencyRepository.find(EscrowOperation.REFUND, key);
        if (seen.isPresent()) {
            return inOrderTransaction(orderNumber, () -> replaySettled(seen.get(), fingerprint));
        }

        try {
            return inOrderTransaction(orderNumber,
                () -> applyRefund(orderNumber, reason, key, source, fingerprint)
            );
        } catch (DuplicateIdempotencyKeyException duplicateKey) {
            return inOrderTransaction(orderNumber, () -> replaySettled(
                idempotencyRepository.find(EscrowOperation.REFUND, key)
                    .orElseThrow(() -> duplicateKey),
                fingerprint
            ));
        }
    }

    public EscrowHold findByOrderNumber(String orderNumber) {
        return escrowRepository.findByOrderNumber(orderNumber).orElse(null);
    }

    public void processAutoReleaseJob() {
        List<EscrowHold> pendingHolds = escrowRepository.findPendingAutoReleaseHolds();
        for (EscrowHold hold : pendingHolds) {
            releaseEscrow(hold.orderNumber());
        }
    }

    private EscrowOutcome applyCreateHold(
        CreateEscrowHoldCommand command, String key, IdempotencyKeySource source
    ) {
        EscrowIdempotencyRecord record = idempotencyRepository.save(EscrowIdempotencyRecord.opening(
            EscrowOperation.CREATE_HOLD, key, source, command.orderNumber(),
            command.requestFingerprint(), null));

        if (idempotencyRepository.existsFor(EscrowOperation.REFUND, command.orderNumber())) {
            throw new OrderAlreadyUnwoundException("escrow for order " + command.orderNumber()
                + " has already been refunded and cannot be held again"
            );
        }

        EscrowHold hold = escrowRepository.save(EscrowHold.createNewHold(
            command.orderNumber(),
            command.customerPrincipalId(),
            command.merchantPrincipalId(),
            command.driverPrincipalId(),
            command.totalOrderAmount(),
            command.merchantAmount(),
            command.shippingFeeAmount()
        ));

        CustomerWallet customerWallet = customerWalletRepository
            .findByCustomerPrincipalIdForUpdate(command.customerPrincipalId())
            .orElseGet(() -> CustomerWallet.createInitial(command.customerPrincipalId()));
        customerWalletRepository.save(customerWallet.deductForCheckout(command.totalOrderAmount()));

        MerchantWallet merchantWallet = merchantWalletRepository
            .findByMerchantPrincipalIdForUpdate(command.merchantPrincipalId())
            .orElseGet(() -> MerchantWallet.createInitial(command.merchantPrincipalId()));
        merchantWalletRepository.save(merchantWallet.addPendingEscrow(command.merchantAmount()));

        if (hasDriver(command.driverPrincipalId())) {
            DriverWallet driverWallet = driverWalletRepository
                .findByDriverPrincipalIdForUpdate(command.driverPrincipalId())
                .orElseGet(() -> DriverWallet.createInitial(command.driverPrincipalId()));
            driverWalletRepository.save(driverWallet.addPendingEscrow(command.shippingFeeAmount()));
        }

        recordLedgerEntry(PaymentTransaction.TransactionType.CHECKOUT_PAYMENT,
            command.orderNumber(), command.customerPrincipalId(), command.totalOrderAmount()
        );

        LOG.debug("escrow idempotency: first sight of key {} for CREATE_HOLD on order {}",
            key, command.orderNumber()
        );
        return new EscrowOutcome(link(record, hold), false);
    }

    private EscrowOutcome replayCreateHold(EscrowIdempotencyRecord record, String fingerprint) {
        requireSameRequest(record, fingerprint);

        EscrowHold hold = escrowRepository.findByOrderNumberForUpdate(record.orderNumber())
            .orElseThrow(() -> new EscrowNotFoundException("Escrow hold not found for order: "
                + record.orderNumber()
            ));

        requireStillHeld(hold, "held again");

        LOG.warn("escrow idempotency: CREATE_HOLD for order {} is a replay of key {} first applied "
                + "at {}; replying already_applied",
            record.orderNumber(), record.idempotencyKey(), record.createdAt()
        );
        return new EscrowOutcome(hold, true);
    }

    private EscrowOutcome recoverFromDuplicateOrderNumber(
        CreateEscrowHoldCommand command, String key, IdempotencyKeySource source
    ) {
        EscrowHold hold = inOrderTransaction(command.orderNumber(), () -> {
            EscrowHold existing = escrowRepository.findByOrderNumberForUpdate(command.orderNumber())
                .orElseThrow(() -> new EscrowNotFoundException("Escrow hold not found for order: "
                    + command.orderNumber()
                ));
            requireStillHeld(existing, "held again");

            String persisted = EscrowRequestFingerprint.forCreateHold(existing);
            if (!persisted.equals(command.requestFingerprint())) {
                LOG.error("escrow idempotency CONFLICT: order {} already holds escrow with "
                        + "fingerprint {}, and key {} has now arrived for it with fingerprint {}; "
                        + "refusing and applying nothing",
                    command.orderNumber(), persisted, key, command.requestFingerprint()
                );
                throw new IdempotencyConflictException("escrow for order " + command.orderNumber()
                    + " already exists on different terms"
                );
            }
            return existing;
        });

        try {
            inOrderTransaction(command.orderNumber(), () -> idempotencyRepository.save(
                EscrowIdempotencyRecord.opening(EscrowOperation.CREATE_HOLD, key, source,
                    command.orderNumber(), command.requestFingerprint(), null
                ).appliedTo(hold.id())
            ));
        } catch (DuplicateIdempotencyKeyException concurrentAlias) {
            LOG.debug("escrow idempotency: the alias for key {} on order {} was written "
                + "concurrently", key, command.orderNumber()
            );
        }

        LOG.warn("escrow idempotency: CREATE_HOLD for order {} arrived under a new key {} with the "
            + "same terms as the live hold; replying already_applied", command.orderNumber(), key
        );
        return new EscrowOutcome(hold, true);
    }

    private EscrowOutcome applyRelease(
        String orderNumber, String key, IdempotencyKeySource source, String fingerprint
    ) {
        EscrowIdempotencyRecord record = idempotencyRepository.save(EscrowIdempotencyRecord.opening(
            EscrowOperation.RELEASE, key, source, orderNumber, fingerprint, null));

        EscrowHold hold = escrowRepository.findByOrderNumberForUpdate(orderNumber)
            .orElseThrow(() -> new EscrowNotFoundException(
                "Escrow hold not found for order: " + orderNumber
            ));

        if (hold.status() == EscrowHold.EscrowStatus.RELEASED) {
            return new EscrowOutcome(link(record, hold), true);
        }
        requireStillHeld(hold, "released");

        MerchantWallet merchantWallet = merchantWalletRepository
            .findByMerchantPrincipalIdForUpdate(hold.merchantPrincipalId())
            .orElseGet(() -> MerchantWallet.createInitial(hold.merchantPrincipalId()));
        merchantWalletRepository.save(merchantWallet.releaseEscrowToAvailable(hold.merchantAmount()));

        if (hasDriver(hold.driverPrincipalId())) {
            DriverWallet driverWallet = driverWalletRepository
                .findByDriverPrincipalIdForUpdate(hold.driverPrincipalId())
                .orElseGet(() -> DriverWallet.createInitial(hold.driverPrincipalId()));
            driverWalletRepository.save(driverWallet.releaseEscrowToAvailable(hold.shippingFeeAmount()));
        }

        EscrowHold released = escrowRepository.save(hold.markAsReleased());
        recordLedgerEntry(PaymentTransaction.TransactionType.ESCROW_RELEASE,
            orderNumber, hold.merchantPrincipalId(), hold.merchantAmount());

        return new EscrowOutcome(link(record, released), false);
    }

    private EscrowOutcome applyRefund(
        String orderNumber, String reason, String key, IdempotencyKeySource source, String fingerprint
    ) {
        EscrowIdempotencyRecord record = idempotencyRepository.save(EscrowIdempotencyRecord.opening(
            EscrowOperation.REFUND, key, source, orderNumber, fingerprint, trimDetail(reason)
        ));

        EscrowHold hold = escrowRepository.findByOrderNumberForUpdate(orderNumber).orElse(null);
        if (hold == null) {
            LOG.warn("escrow idempotency: REFUND of order {} found no hold to unwind; recording a "
                + "tombstone under key {}", orderNumber, key
            );
            return new EscrowOutcome(null, false);
        }
        return refundLockedHold(record, hold);
    }

    private EscrowOutcome resumeTombstonedRefund(EscrowIdempotencyRecord record) {
        EscrowHold hold = escrowRepository.findByOrderNumberForUpdate(record.orderNumber())
            .orElse(null);
        if (hold == null) {
            return new EscrowOutcome(null, true);
        }

        LOG.warn("escrow idempotency: REFUND of order {} found no hold when it first ran and a hold "
            + "has committed since; unwinding it now under key {}",
            record.orderNumber(), record.idempotencyKey()
        );
        return refundLockedHold(record, hold);
    }

    private EscrowOutcome refundLockedHold(EscrowIdempotencyRecord record, EscrowHold hold) {
        if (hold.status() == EscrowHold.EscrowStatus.REFUNDED) {
            return new EscrowOutcome(link(record, hold), true);
        }
        if (hold.status() == EscrowHold.EscrowStatus.RELEASED) {
            throw new DomainException("escrow for order " + hold.orderNumber()
                + " was already released and cannot be refunded here"
            );
        }
        requireStillHeld(hold, "refunded");

        CustomerWallet customerWallet = customerWalletRepository
            .findByCustomerPrincipalIdForUpdate(hold.customerPrincipalId())
            .orElseGet(() -> CustomerWallet.createInitial(hold.customerPrincipalId()));
        customerWalletRepository.save(customerWallet.topUp(hold.totalOrderAmount()));

        MerchantWallet merchantWallet = merchantWalletRepository
            .findByMerchantPrincipalIdForUpdate(hold.merchantPrincipalId())
            .orElseGet(() -> MerchantWallet.createInitial(hold.merchantPrincipalId()));
        merchantWalletRepository.save(merchantWallet.cancelPendingEscrow(hold.merchantAmount()));

        if (hasDriver(hold.driverPrincipalId())) {
            DriverWallet driverWallet = driverWalletRepository
                .findByDriverPrincipalIdForUpdate(hold.driverPrincipalId())
                .orElseGet(() -> DriverWallet.createInitial(hold.driverPrincipalId()));
            driverWalletRepository.save(driverWallet.cancelPendingEscrow(hold.shippingFeeAmount()));
        }

        EscrowHold refunded = escrowRepository.save(hold.markAsRefunded());
        recordLedgerEntry(PaymentTransaction.TransactionType.REFUND,
            hold.orderNumber(), hold.customerPrincipalId(), hold.totalOrderAmount());

        return new EscrowOutcome(link(record, refunded), false);
    }

    private EscrowOutcome replaySettled(EscrowIdempotencyRecord record, String fingerprint) {
        requireSameRequest(record, fingerprint);

        if (record.isTombstone()) {
            return resumeTombstonedRefund(record);
        }

        EscrowHold hold = escrowRepository.findByOrderNumberForUpdate(record.orderNumber())
            .orElseThrow(() -> new EscrowNotFoundException("Escrow hold not found for order: "
                + record.orderNumber()
            ));

        LOG.warn("escrow idempotency: {} for order {} is a replay of key {} first applied at {}; "
                + "replying already_applied",
            record.operation(), record.orderNumber(), record.idempotencyKey(), record.createdAt()
        );
        return new EscrowOutcome(hold, true);
    }

    private EscrowHold link(EscrowIdempotencyRecord record, EscrowHold hold) {
        idempotencyRepository.save(record.appliedTo(hold.id()));
        return hold;
    }

    private void recordLedgerEntry(
        PaymentTransaction.TransactionType type, String orderNumber, String principalId, BigDecimal amount
    ) {
        paymentTransactionRepository.save(new PaymentTransaction(
            null,
            ledgerReference(type, orderNumber),
            null,
            principalId,
            type,
            PaymentTransaction.PaymentMethod.INTERNAL_WALLET,
            amount,
            PaymentTransaction.TransactionStatus.SUCCESS,
            null,
            Instant.now()
        ));
    }

    private static String ledgerReference(
        PaymentTransaction.TransactionType type, String orderNumber
    ) {
        String prefix = switch (type) {
            case CHECKOUT_PAYMENT -> "escrow:hold:";
            case ESCROW_RELEASE -> "escrow:release:";
            case REFUND -> "escrow:refund:";
            case TOPUP -> throw new IllegalArgumentException("a top-up is not an escrow movement");
        };
        return prefix + orderNumber;
    }

    private void requireSameRequest(EscrowIdempotencyRecord record, String fingerprint) {
        if (record.requestFingerprint().equals(fingerprint)) {
            return;
        }
        LOG.error("escrow idempotency CONFLICT: key {} was first used for {} on order {} with "
                + "fingerprint {} at {}, and has now arrived with fingerprint {}; refusing and "
                + "applying nothing",
            record.idempotencyKey(), record.operation(), record.orderNumber(),
            record.requestFingerprint(), record.createdAt(), fingerprint);
        throw new IdempotencyConflictException("idempotency key " + record.idempotencyKey()
            + " was already used for a different " + record.operation() + " request"
        );
    }

    private static void requireStillHeld(EscrowHold hold, String verb) {
        if (hold.status() == EscrowHold.EscrowStatus.HELD) {
            return;
        }
        throw new DomainException("escrow for order " + hold.orderNumber() + " is "
            + hold.status() + " and cannot be " + verb
        );
    }

    private static boolean hasDriver(String driverPrincipalId) {
        return driverPrincipalId != null && !driverPrincipalId.isBlank();
    }

    private static String resolveKey(String supplied, String orderNumber) {
        if (supplied == null || supplied.isBlank()) {
            return "order:" + orderNumber;
        }
        if (supplied.length() > MAX_CALLER_KEY_LENGTH) {
            throw new IllegalArgumentException(
                "an idempotency key may be at most " + MAX_CALLER_KEY_LENGTH + " characters"
            );
        }
        return supplied;
    }

    private static IdempotencyKeySource sourceOf(
        String supplied, EscrowOperation operation, String orderNumber
    ) {
        if (supplied != null && !supplied.isBlank()) {
            return IdempotencyKeySource.CALLER;
        }
        LOG.warn("escrow idempotency: {} arrived for order {} with no idempotency key; deduping on "
            + "a key derived from the order number. The caller should send "
            + "common.v1.IdempotencyKey.", operation, orderNumber
        );
        return IdempotencyKeySource.DERIVED;
    }

    private static String trimDetail(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() <= MAX_DETAIL_LENGTH ? reason : reason.substring(0, MAX_DETAIL_LENGTH);
    }
}
