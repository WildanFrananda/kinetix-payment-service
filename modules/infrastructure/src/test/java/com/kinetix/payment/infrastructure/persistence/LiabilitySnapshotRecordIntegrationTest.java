package com.kinetix.payment.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = PersistenceTestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
    "spring.datasource.url=${PAYMENT_TEST_DB_URL}",
    "spring.datasource.username=${PAYMENT_TEST_DB_USERNAME}",
    "spring.datasource.password=${PAYMENT_TEST_DB_PASSWORD}",
    "spring.liquibase.enabled=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class LiabilitySnapshotRecordIntegrationTest {
    @Autowired private LiabilitySnapshotRecordAdapter records;

    @Test
    void aRecordReadsBackExactlyAsItWasKept() {
        UUID id = UUID.randomUUID();
        LiabilitySnapshotRecord kept = new LiabilitySnapshotRecord(
            id,
            Instant.parse("2026-10-12T09:00:00.123456Z"),
            "IDR",
            8,
            new BigDecimal("92233720368547758.07"),
            "89276c0d19d674863ff54989337008621a5774b1a5226f6fbd5029b30bb0bd8f"
        );

        records.save(kept);

        assertEquals(Optional.of(kept), records.find(id));
    }

    @Test
    void aSnapshotNeverRecordedIsNotFound() {
        assertTrue(records.find(UUID.randomUUID()).isEmpty());
    }
}
