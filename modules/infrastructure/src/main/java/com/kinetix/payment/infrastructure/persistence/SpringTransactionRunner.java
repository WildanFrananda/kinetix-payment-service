package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.port.TransactionRunnerPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.function.Supplier;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringTransactionRunner implements TransactionRunnerPort {
    private static final int TRANSACTION_TIMEOUT_SECONDS = 4;
    private static final String LOCK_TIMEOUT = "SET LOCAL lock_timeout = '3s'";

    private final TransactionTemplate template;
    private final EntityManagerFactory entityManagerFactory;

    public SpringTransactionRunner(
        PlatformTransactionManager transactionManager, EntityManagerFactory entityManagerFactory
    ) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public <T> T inNewTransaction(Supplier<T> work) {
        return template.execute(status -> {
            EntityManager entityManager =
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            entityManager.createNativeQuery(LOCK_TIMEOUT).executeUpdate();
            return work.get();
        });
    }
}
