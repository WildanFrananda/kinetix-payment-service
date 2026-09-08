package com.kinetix.payment.infrastructure.persistence;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

public class CapturedStatements implements StatementInspector {
    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql.toLowerCase(Locale.ROOT));
        return sql;
    }

    static void forget() {
        STATEMENTS.clear();
    }

    static boolean sawSelectOn(String table, String lockClause) {
        return STATEMENTS.stream()
            .anyMatch(sql -> sql.startsWith("select") && sql.contains(table) && sql.contains(lockClause));
    }

    static List<String> selectsOn(String table) {
        return STATEMENTS.stream()
            .filter(sql -> sql.startsWith("select") && sql.contains(table))
            .toList();
    }
}
