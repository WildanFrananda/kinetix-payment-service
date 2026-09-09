package com.kinetix.payment.infrastructure.grpc;

import io.grpc.BindableService;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@GrpcGlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GrpcServerMetricsInterceptor implements ServerInterceptor {
    private static final String CALLS = "kinetix.grpc.server.calls";

    private final MeterRegistry registry;

    private final Collection<BindableService> services;

    public GrpcServerMetricsInterceptor(MeterRegistry registry, Collection<BindableService> services) {
        this.registry = registry;
        this.services = services;
    }

    @PostConstruct
    void seedEveryBoundMethod() {
        for (BindableService service : services) {
            for (ServerMethodDefinition<?, ?> method : service.bindService().getMethods()) {
                counter(method.getMethodDescriptor().getFullMethodName(), Status.Code.OK);
            }
        }
    }

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next
    ) {
        String method = call.getMethodDescriptor().getFullMethodName();
        AtomicBoolean counted = new AtomicBoolean(false);

        ServerCall<ReqT, RespT> counting = new SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                count(counted, method, status.getCode());
                super.close(status, trailers);
            }
        };

        return new SimpleForwardingServerCallListener<>(next.startCall(counting, headers)) {
            @Override
            public void onCancel() {
                count(counted, method, Status.Code.CANCELLED);
                super.onCancel();
            }
        };
    }

    private void count(AtomicBoolean counted, String method, Status.Code code) {
        if (!counted.compareAndSet(false, true)) {
            return;
        }
        counter(method, code).increment();
    }

    private Counter counter(String method, Status.Code code) {
        return Counter.builder(CALLS)
            .description("gRPC calls this server has finished, by method and final status code.")
            .tag("grpc_method", method)
            .tag("grpc_code", code.name())
            .register(registry);
    }
}
