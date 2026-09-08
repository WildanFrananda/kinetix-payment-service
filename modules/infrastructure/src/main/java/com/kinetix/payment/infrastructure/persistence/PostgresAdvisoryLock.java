package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.port.AdvisoryLockPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PostgresAdvisoryLock implements AdvisoryLockPort {
    private static final int ORDER_NUMBER_NAMESPACE = 1;
    private static final int WALLET_OWNER_NAMESPACE = 2;

    private static final String ACQUIRE = "SELECT pg_advisory_xact_lock(?, hashtext(?))";

    private final EntityManagerFactory entityManagerFactory;

    public PostgresAdvisoryLock(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void lockOrder(String orderNumber) {
        acquire(ORDER_NUMBER_NAMESPACE, orderNumber);
    }

    @Override
    public void lockWalletOwner(String principalId) {
        acquire(WALLET_OWNER_NAMESPACE, principalId);
    }

    private void acquire(int namespace, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("an advisory lock needs a key to hash");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "pg_advisory_xact_lock is released at the end of the transaction that took it; "
                    + "taking one outside a transaction locks nothing"
            );
        }
        EntityManager entityManager =
            EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE)) {
                statement.setInt(1, namespace);
                statement.setString(2, key);
                statement.execute();
            }
        });
    }
}
