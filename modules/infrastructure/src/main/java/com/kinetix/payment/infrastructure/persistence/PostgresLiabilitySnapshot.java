package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.entity.SuspenseWallet;
import com.kinetix.payment.domain.port.LiabilitySnapshotPort;
import com.kinetix.payment.domain.port.LiabilitySnapshotVisitor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PostgresLiabilitySnapshot implements LiabilitySnapshotPort {
    private static final int FETCH_SIZE = 500;
    private static final String STATEMENT_TIMEOUT = "SET LOCAL statement_timeout = '120s'";
    private static final String STALL_TIMEOUT = "SET LOCAL idle_in_transaction_session_timeout = '60s'";
    private static final String SNAPSHOT_FACTS = "SELECT clock_timestamp(), "
        + "current_setting('transaction_isolation'), current_setting('transaction_read_only')";
    private static final String CUSTOMERS = "SELECT id, customer_principal_id, balance, held_balance, currency, "
        + "created_at, updated_at FROM customer_wallets WHERE currency = ? ORDER BY id";
    private static final String MERCHANTS = "SELECT id, merchant_principal_id, available_balance, "
        + "pending_escrow_balance, currency, created_at, updated_at FROM merchant_wallets WHERE currency = ? ORDER BY id";
    private static final String DRIVERS = "SELECT id, driver_principal_id, available_balance, "
        + "pending_escrow_balance, currency, created_at, updated_at FROM driver_wallets WHERE currency = ? ORDER BY id";
    private static final String SUSPENSE = "SELECT id, purpose, available_balance, "
        + "pending_escrow_balance, currency, created_at, updated_at FROM suspense_wallets WHERE currency = ? ORDER BY id";

    private final TransactionTemplate template;
    private final EntityManagerFactory entityManagerFactory;

    public PostgresLiabilitySnapshot(
        PlatformTransactionManager transactionManager, EntityManagerFactory entityManagerFactory
    ) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.template.setReadOnly(true);
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void read(String currency, LiabilitySnapshotVisitor visitor) {
        template.executeWithoutResult(status -> {
            EntityManager entityManager =
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            entityManager.unwrap(Session.class).doWork(connection -> {
                bound(connection);
                visitor.asOf(snapshotTakenAt(connection));
                stream(connection, CUSTOMERS, currency, visitor, PostgresLiabilitySnapshot::customer);
                stream(connection, MERCHANTS, currency, visitor, PostgresLiabilitySnapshot::merchant);
                stream(connection, DRIVERS, currency, visitor, PostgresLiabilitySnapshot::driver);
                stream(connection, SUSPENSE, currency, visitor, PostgresLiabilitySnapshot::suspense);
            });
        });
    }

    private static void bound(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(STATEMENT_TIMEOUT);
            statement.execute(STALL_TIMEOUT);
        }
    }

    private static Instant snapshotTakenAt(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet facts = statement.executeQuery(SNAPSHOT_FACTS)) {
            facts.next();
            String isolation = facts.getString(2);
            String readOnly = facts.getString(3);
            if (!"repeatable read".equals(isolation) || !"on".equals(readOnly)) {
                throw new IllegalStateException(
                    "a liability snapshot must read one instant, but its transaction runs " + isolation
                        + " with read_only " + readOnly
                );
            }
            return facts.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static void stream(
        Connection connection,
        String query,
        String currency,
        LiabilitySnapshotVisitor visitor,
        WalletRowMapper mapper
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setFetchSize(FETCH_SIZE);
            statement.setString(1, currency);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    visitor.holder(mapper.map(rows));
                }
            }
        }
    }

    private static CustomerWallet customer(ResultSet row) throws SQLException {
        return new CustomerWallet(
            row.getLong("id"),
            row.getString("customer_principal_id"),
            row.getBigDecimal("balance"),
            row.getBigDecimal("held_balance"),
            row.getString("currency"),
            instant(row, "created_at"),
            instant(row, "updated_at")
        );
    }

    private static MerchantWallet merchant(ResultSet row) throws SQLException {
        return new MerchantWallet(
            row.getLong("id"),
            row.getString("merchant_principal_id"),
            row.getBigDecimal("available_balance"),
            row.getBigDecimal("pending_escrow_balance"),
            row.getString("currency"),
            instant(row, "created_at"),
            instant(row, "updated_at")
        );
    }

    private static DriverWallet driver(ResultSet row) throws SQLException {
        return new DriverWallet(
            row.getLong("id"),
            row.getString("driver_principal_id"),
            row.getBigDecimal("available_balance"),
            row.getBigDecimal("pending_escrow_balance"),
            row.getString("currency"),
            instant(row, "created_at"),
            instant(row, "updated_at")
        );
    }

    private static SuspenseWallet suspense(ResultSet row) throws SQLException {
        return new SuspenseWallet(
            row.getLong("id"),
            row.getString("purpose"),
            row.getBigDecimal("available_balance"),
            row.getBigDecimal("pending_escrow_balance"),
            row.getString("currency"),
            instant(row, "created_at"),
            instant(row, "updated_at")
        );
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
