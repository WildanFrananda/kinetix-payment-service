package com.kinetix.payment.infrastructure.grpc;

import java.net.URI;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class SpiffeId {
    private static final String DEFAULT_TRUST_DOMAIN = "kinetix.local";

    static final List<String> TRUST_PREFIXES = configuredTrustDomains().stream()
            .map(SpiffeId::prefixFor)
            .toList();

    static final String TRUST_DOMAIN = TRUST_PREFIXES.get(0);

    private static final int URI_SAN = 6;

    private SpiffeId() {}

    private static List<String> configuredTrustDomains() {
        String configured = System.getenv("KINETIX_TRUST_DOMAIN");
        if (configured == null || configured.isBlank()) {
            return List.of(DEFAULT_TRUST_DOMAIN);
        }

        List<String> named = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();

        return named.isEmpty() ? List.of(DEFAULT_TRUST_DOMAIN) : named;
    }

    static String prefixFor(String domain) {
        return "spiffe://" + domain + "/service/";
    }

    static Optional<String> serviceIn(String id, String prefix) {
        if (id == null || !id.startsWith(prefix)) {
            return Optional.empty();
        }
        String service = id.substring(prefix.length());
        if (service.isEmpty() || service.contains("/")) {
            return Optional.empty();
        }
        return Optional.of(service);
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
            String id = uri.toString();
            Optional<String> service = TRUST_PREFIXES.stream()
                    .map(prefix -> serviceIn(id, prefix))
                    .flatMap(Optional::stream)
                    .findFirst();

            if (service.isEmpty()) {
                continue;
            }
            return service;
        }
        return Optional.empty();
    }
}
