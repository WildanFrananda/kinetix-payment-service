package com.kinetix.payment.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonConfiguration {
    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }
}
