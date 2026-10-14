package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilityAccount;
import com.kinetix.payment.domain.entity.MinorUnits;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class LiabilitySnapshotDigest {
    private static final byte[] TAG = "kinetix.payment.liability-snapshot.v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_HOLDER_BYTES = 255;
    private static final Comparator<LiabilityDigestRow> CANONICAL = Comparator
        .comparingInt(LiabilityDigestRow::kind)
        .thenComparing(LiabilityDigestRow::holder, Arrays::compareUnsigned);

    private final List<LiabilityDigestRow> rows = new ArrayList<>();

    void add(LiabilityAccount account) {
        byte[] holder = account.holder().getBytes(StandardCharsets.UTF_8);
        if (holder.length > MAX_HOLDER_BYTES) {
            throw new IllegalStateException(
                "the " + account.kind() + " holder is " + holder.length + " bytes, and a snapshot names at most "
                    + MAX_HOLDER_BYTES
            );
        }

        rows.add(new LiabilityDigestRow(account.kind().code(), holder, MinorUnits.of(account.balance())));
    }

    String digest(Instant asOf, String currency, long accountCount, long totalMinor) {
        List<LiabilityDigestRow> ordered = new ArrayList<>(rows);
        ordered.sort(CANONICAL);

        MessageDigest sha = sha256();
        sha.update(TAG);
        sha.update(
            ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                .putLong(asOf.getEpochSecond())
                .putInt(asOf.getNano())
                .array()
        );
        sha.update(currency.getBytes(StandardCharsets.US_ASCII));
        sha.update(ByteBuffer.allocate(2 * Long.BYTES).putLong(accountCount).putLong(totalMinor).array());
        for (LiabilityDigestRow row : ordered) {
            sha.update((byte) row.kind());
            sha.update((byte) row.holder().length);
            sha.update(row.holder());
            sha.update(ByteBuffer.allocate(Long.BYTES).putLong(row.amount()).array());
        }

        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("this JVM offers no SHA-256", missing);
        }
    }
}
