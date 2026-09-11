package com.kinetix.payment.infrastructure.migration;

import identity.v1.Identity;
import identity.v1.IdentityServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

public class ResolvePrincipalBackfill implements CustomTaskChange {
    private static final List<Column> COLUMNS = List.of(
        new Column("customer_wallets", "customer_id", "customer_principal_id"),
        new Column("merchant_wallets", "merchant_id", "merchant_principal_id"),
        new Column("driver_wallets", "driver_id", "driver_principal_id"),
        new Column("escrow_holds", "customer_id", "customer_principal_id"),
        new Column("escrow_holds", "merchant_id", "merchant_principal_id"),
        new Column("escrow_holds", "driver_id", "driver_principal_id"),
        new Column("payment_transactions", "user_id", "principal_id")
    );

    private record Column(String table, String legacy, String principal) {}

    private String summary = "no rows needed a principal";

    @Override
    public void execute(Database database) throws CustomChangeException {
        Connection connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();

        Map<Long, String> resolved = new LinkedHashMap<>();
        List<String> filled = new ArrayList<>();

        ManagedChannel channel = null;
        try {
            for (Column column : COLUMNS) {
                List<Long> pending = pendingIds(connection, column);
                if (pending.isEmpty()) {
                    continue;
                }
                if (channel == null) {
                    channel = openChannel();
                }
                int rows = 0;
                for (Long legacyId : pending) {
                    String principalId = resolved.get(legacyId);
                    if (principalId == null) {
                        principalId = resolve(channel, legacyId);
                        resolved.put(legacyId, principalId);
                    }
                    rows += apply(connection, column, legacyId, principalId);
                }
                filled.add(rows + " x " + column.table() + "." + column.principal());
            }
        } catch (CustomChangeException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomChangeException("could not backfill principals: " + e.getMessage(), e);
        } finally {
            if (channel != null) {
                channel.shutdownNow();
                try {
                    channel.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (!filled.isEmpty()) {
            summary = "resolved " + resolved.size() + " account(s) to principals: "
                + String.join(", ", filled);
        }
    }

    private ManagedChannel openChannel() throws Exception {
        String target = env("IDENTITY_GRPC_TARGET", "kinetix-identity-service:50052");
        String pki = env("KINETIX_PKI_DIR", "/pki");

        return NettyChannelBuilder.forTarget(target)
            .negotiationType(NegotiationType.TLS)
            .sslContext(GrpcSslContexts.forClient()
                .trustManager(new File(pki, "ca.pem"))
                .keyManager(new File(pki, "tls.crt"), new File(pki, "tls.key"))
                .build()
            )
            .build();
    }

    private String resolve(ManagedChannel channel, Long legacyId) {
        Identity.ResolvePrincipalResponse response = IdentityServiceGrpc
            .newBlockingStub(channel)
            .withDeadlineAfter(15, TimeUnit.SECONDS)
            .resolvePrincipal(Identity.ResolvePrincipalRequest.newBuilder()
                .setServiceLocalId(Identity.ServiceLocalId.newBuilder()
                    .setService("identity")
                    .setLocalId(Long.toString(legacyId))
                    .build()
                )
                .build()
            );

        if (!response.getFound() || response.getPrincipalId().isBlank()) {
            throw new IllegalStateException(
                "identity does not know account " + legacyId + ", so the balance held against it "
                    + "cannot be reached by anyone. Migration stopped with the old columns intact."
            );
        }
        return response.getPrincipalId();
    }

    private List<Long> pendingIds(Connection connection, Column column) throws Exception {
        String sql = "select distinct " + column.legacy() + " from " + column.table()
            + " where " + column.legacy() + " is not null and " + column.principal() + " is null";
        List<Long> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)
        ) {
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    private int apply(Connection connection, Column column, Long legacyId, String principalId) throws Exception {
        String sql = "update " + column.table() + " set " + column.principal() + " = ?"
            + " where " + column.legacy() + " = ? and " + column.principal() + " is null";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, principalId);
            statement.setLong(2, legacyId);
            return statement.executeUpdate();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String getConfirmationMessage() {
        return summary;
    }

    @Override
    public void setUp() throws SetupException {
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
