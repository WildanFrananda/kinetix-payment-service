package com.kinetix.payment.infrastructure.grpc;

import java.net.URI;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class SpiffeId {

    private static final String TRUST_DOMAIN = "spiffe://kinetix.local/service/";

    private static final int URI_SAN = 6;

    private SpiffeId() {
    }

    public static Optional<String> serviceOf(X509Certificate peer) {
        Collection<List<?>> names;
        try {
            names = peer.getSubjectAlternativeNames();
        } catch (CertificateParsingException malformed) {
            return Optional.empty();
        }
        if (names == null) {
            return Optional.empty();
        }

        for (List<?> entry : names) {
            if (entry.size() < 2 || !Integer.valueOf(URI_SAN).equals(entry.get(0))) {
                continue;
            }
            if (!(entry.get(1) instanceof String value)) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(value).normalize();
            } catch (IllegalArgumentException notAUri) {
                continue;
            }
            String normalised = uri.toString();
            if (!normalised.startsWith(TRUST_DOMAIN)) {
                continue;
            }
            String service = normalised.substring(TRUST_DOMAIN.length());
            if (service.isEmpty() || service.contains("/")) {
                continue;
            }
            return Optional.of(service);
        }
        return Optional.empty();
    }
}
