package com.electrahub.aisupport.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class PiiRedactor {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+[A-Za-z0-9._~+/=-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern UUID = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern CARD_NUMBER = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\w)(?:\\+?\\d[ .()-]?){8,15}\\d(?!\\w)");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s,;]+"
    );

    String redact(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String value = EMAIL.matcher(input).replaceAll("[email-redacted]");
        value = BEARER.matcher(value).replaceAll("Bearer [token-redacted]");
        return JWT.matcher(value).replaceAll("[jwt-redacted]");
    }

    /**
     * Hosted providers receive a stricter representation. It preserves product and charger context,
     * but removes account/session identifiers and user-provided contact or payment details.
     */
    String redactForHostedProvider(String input) {
        String value = redact(input);
        value = UUID.matcher(value).replaceAll("[id-redacted]");
        value = CARD_NUMBER.matcher(value).replaceAll("[payment-redacted]");
        value = PHONE.matcher(value).replaceAll("[phone-redacted]");
        return SECRET_ASSIGNMENT.matcher(value).replaceAll("$1=[secret-redacted]");
    }
}
