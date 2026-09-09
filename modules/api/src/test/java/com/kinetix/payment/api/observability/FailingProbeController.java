package com.kinetix.payment.api.observability;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FailingProbeController {
    @GetMapping("/api/v1/probe/unhandled-failure")
    public String unhandledFailure() {
        throw new IllegalStateException("the escrow database is unreachable");
    }

    @GetMapping("/api/v1/probe/committed-failure")
    public String committedFailure(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.getWriter().write("accepted");
        response.flushBuffer();
        throw new IllegalStateException("failed after the client already had its status line");
    }
}
