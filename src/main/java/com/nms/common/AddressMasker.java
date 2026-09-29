package com.nms.common;

import com.nms.common.domain.Channel;

/** The single place where contact addresses are masked for responses, logs, and audit. */
public final class AddressMasker {

    private static final String MASK = "***";

    private AddressMasker() {
    }

    public static String mask(Channel channel, String address) {
        if (address == null || address.isBlank()) {
            return MASK;
        }
        return switch (channel) {
            case EMAIL -> maskEmail(address);
            case SMS -> maskPhone(address);
            case PUSH -> maskToken(address);
        };
    }

    private static String maskEmail(String address) {
        int at = address.indexOf('@');
        if (at <= 0 || at == address.length() - 1) {
            return MASK;
        }
        return address.charAt(0) + MASK + address.substring(at);
    }

    private static String maskPhone(String address) {
        String digits = address.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return MASK;
        }
        return "***-***-" + digits.substring(digits.length() - 4);
    }

    private static String maskToken(String address) {
        if (address.length() <= 4) {
            return MASK;
        }
        return MASK + address.substring(address.length() - 4);
    }
}
