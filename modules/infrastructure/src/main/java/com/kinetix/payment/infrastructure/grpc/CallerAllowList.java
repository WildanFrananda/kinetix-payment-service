package com.kinetix.payment.infrastructure.grpc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

final class CallerAllowList {
    private final Set<String> unrestricted;
    private final Map<String, Set<String>> scoped;

    private CallerAllowList(Set<String> unrestricted, Map<String, Set<String>> scoped) {
        this.unrestricted = unrestricted;
        this.scoped = scoped;
    }

    static CallerAllowList parse(String raw) {
        Set<String> unrestricted = new LinkedHashSet<>();
        Map<String, Set<String>> scoped = new LinkedHashMap<>();
        Arrays.stream(raw == null ? new String[0] : raw.split(","))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .forEach(entry -> {
                int at = entry.indexOf('@');
                if (at < 0) {
                    unrestricted.add(entry);
                    return;
                }

                String caller = entry.substring(0, at).trim();
                String service = entry.substring(at + 1).trim();
                if (caller.isEmpty() || service.isEmpty() || service.contains("@")) {
                    throw new IllegalStateException(
                        "'" + entry + "' in kinetix.grpc.allowed-callers is neither a service "
                            + "nor service@package.Service"
                    );
                }

                scoped.computeIfAbsent(caller, ignored -> new LinkedHashSet<>()).add(service);
            });
        unrestricted.forEach(scoped::remove);
        return new CallerAllowList(Set.copyOf(unrestricted), Map.copyOf(scoped));
    }

    boolean isEmpty() {
        return unrestricted.isEmpty() && scoped.isEmpty();
    }

    boolean permits(String caller, String service) {
        if (unrestricted.contains(caller)) {
            return true;
        }

        Set<String> services = scoped.get(caller);
        return services != null && services.contains(service);
    }

    String describe() {
        Set<String> described = new TreeSet<>();
        unrestricted.forEach(caller -> described.add(caller + " (any service)"));
        scoped.forEach(
            (caller, services) ->
                described.add(caller + " (" + String.join(" ", new TreeSet<>(services)) + ")")
        );
        return described.stream().collect(Collectors.joining(", "));
    }
}
