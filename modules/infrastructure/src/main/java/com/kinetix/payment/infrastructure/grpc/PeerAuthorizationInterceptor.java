package com.kinetix.payment.infrastructure.grpc;

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@GrpcGlobalServerInterceptor
public class PeerAuthorizationInterceptor implements ServerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PeerAuthorizationInterceptor.class);

    private final Set<String> allowed;

    @SuppressWarnings("null")
    public PeerAuthorizationInterceptor(@Value("${kinetix.grpc.allowed-callers:}") String raw) {
        this.allowed = Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        if (allowed.isEmpty()) {
            throw new IllegalStateException(
                "kinetix.grpc.allowed-callers is empty. Name the services permitted to call this "
                    + "server, or the gRPC surface is unreachable.");
        }
        LOG.info("gRPC callers allowed on this server: {}", allowed);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next
    ) {
        Optional<String> peer = peerService(call);

        if (peer.isEmpty()) {
            LOG.warn("refused a gRPC call to {} from a peer with no SPIFFE identity",
                call.getMethodDescriptor().getFullMethodName());
            call.close(Status.UNAUTHENTICATED.withDescription(
                "a client certificate carrying a SPIFFE id is required"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        String service = peer.get();
        if (!allowed.contains(service)) {
            LOG.warn("refused a gRPC call to {} from {}, which is not on the allow list",
                call.getMethodDescriptor().getFullMethodName(), service);
            call.close(Status.PERMISSION_DENIED.withDescription(
                "service '" + service + "' may not call this server"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        return next.startCall(call, headers);
    }

    private Optional<String> peerService(ServerCall<?, ?> call) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            return Optional.empty();
        }

        Certificate[] chain;
        try {
            chain = session.getPeerCertificates();
        } catch (javax.net.ssl.SSLPeerUnverifiedException unverified) {
            return Optional.empty();
        }
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
            return Optional.empty();
        }

        return SpiffeId.serviceOf(leaf);
    }
}
