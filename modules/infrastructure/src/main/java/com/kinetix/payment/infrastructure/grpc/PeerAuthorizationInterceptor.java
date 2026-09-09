package com.kinetix.payment.infrastructure.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
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
import javax.net.ssl.SSLPeerUnverifiedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@GrpcGlobalServerInterceptor
public class PeerAuthorizationInterceptor implements ServerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(PeerAuthorizationInterceptor.class);

    private static final Metadata.Key<String> REQUEST_ID =
        Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    private static final String MDC_REQUEST_ID = "requestId";

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
                    + "server, or the gRPC surface is unreachable."
                );
        }
        LOG.info("gRPC callers allowed on this server: {}", allowed);
    }

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next
    ) {
        String requestId = requestIdOf(headers);
        if (requestId != null) {
            MDC.put(MDC_REQUEST_ID, requestId);
        }
        try {
            return authorize(call, headers, next, requestId);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private <ReqT, RespT> Listener<ReqT> authorize(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next,
        String requestId
    ) {
        Optional<String> peer = peerService(call);

        if (peer.isEmpty()) {
            LOG.warn("refused a gRPC call to {} from a peer with no SPIFFE identity",
                call.getMethodDescriptor().getFullMethodName()
            );
            call.close(Status.UNAUTHENTICATED.withDescription(
                "a client certificate carrying a SPIFFE id is required"
            ), new Metadata());
            return new Listener<>() {};
        }

        String service = peer.get();
        if (!allowed.contains(service)) {
            LOG.warn("refused a gRPC call to {} from {}, which is not on the allow list",
                call.getMethodDescriptor().getFullMethodName(), service
            );
            call.close(Status.PERMISSION_DENIED.withDescription(
                "service '" + service + "' may not call this server"), new Metadata()
            );
            return new Listener<>() {};
        }

        LOG.info("gRPC {} from {}", call.getMethodDescriptor().getFullMethodName(), service);

        return withRequestId(next.startCall(call, headers), requestId);
    }

    private <ReqT> Listener<ReqT> withRequestId(
        Listener<ReqT> delegate, String requestId
    ) {
        if (requestId == null) {
            return delegate;
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                runWithRequestId(() -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                runWithRequestId(super::onHalfClose);
            }

            private void runWithRequestId(Runnable work) {
                MDC.put(MDC_REQUEST_ID, requestId);
                try {
                    work.run();
                } finally {
                    MDC.remove(MDC_REQUEST_ID);
                }
            }
        };
    }

    private String requestIdOf(Metadata headers) {
        String value = headers.get(REQUEST_ID);
        return value == null || value.isBlank() ? null : value;
    }

    private Optional<String> peerService(ServerCall<?, ?> call) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            return Optional.empty();
        }

        Certificate[] chain;
        try {
            chain = session.getPeerCertificates();
        } catch (SSLPeerUnverifiedException unverified) {
            return Optional.empty();
        }
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
            return Optional.empty();
        }

        return SpiffeId.serviceOf(leaf);
    }
}
