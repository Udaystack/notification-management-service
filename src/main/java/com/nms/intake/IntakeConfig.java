package com.nms.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.common.config.NmsProperties;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IntakeConfig {

    @Bean
    RequestValidator requestValidator(Validator validator, ObjectMapper objectMapper, NmsProperties properties) {
        return new RequestValidator(validator, objectMapper, properties.intake().maxRecipients());
    }
}
