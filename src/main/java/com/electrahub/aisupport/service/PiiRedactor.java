package com.electrahub.aisupport.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class PiiRedactor {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+[A-Za-z0-9._~+/=-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");

    String redact(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String value = EMAIL.matcher(input).replaceAll("[email-redacted]");
        value = BEARER.matcher(value).replaceAll("Bearer [token-redacted]");
        return JWT.matcher(value).replaceAll("[jwt-redacted]");
    }
}
