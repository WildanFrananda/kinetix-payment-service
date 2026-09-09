package com.kinetix.payment.api.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

public class JsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private static final String REQUEST_ID = "request_id";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(event.getInstant()));
        line.put("level", event.getLevel().toString());
        line.put("logger", event.getLoggerName());
        line.put("thread", event.getThreadName());
        line.put("message", event.getFormattedMessage());
        line.put(REQUEST_ID, event.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY));

        putContext(line, event.getMDCPropertyMap());
        putFailure(line, event.getThrowableProxy());

        return encode(line);
    }

    private static void putContext(Map<String, Object> line, Map<String, String> mdc) {
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            if (RequestIdFilter.MDC_KEY.equals(entry.getKey()) || line.containsKey(entry.getKey())) {
                continue;
            }
            line.put(entry.getKey(), entry.getValue());
        }
    }

    private static void putFailure(Map<String, Object> line, IThrowableProxy failure) {
        if (failure == null) {
            return;
        }
        line.put("error_type", failure.getClassName());
        line.put("error_message", failure.getMessage());
        line.put("error_stack_trace", ThrowableProxyUtil.asString(failure));
    }

    private String encode(Map<String, Object> line) {
        try {
            return mapper.writeValueAsString(line) + "\n";
        } catch (JsonProcessingException unwritable) {
            return "{\"level\":\"ERROR\",\"logger\":\"" + JsonLogFormatter.class.getName()
                + "\",\"message\":\"a log event could not be encoded as JSON\","
                + "\"request_id\":null,\"error_type\":\"" + unwritable.getClass().getName()
                + "\"}\n";
        }
    }
}
