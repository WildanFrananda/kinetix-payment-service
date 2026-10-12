package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.LiabilityHolder;
import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
interface WalletRowMapper {
    LiabilityHolder map(ResultSet row) throws SQLException;
}
