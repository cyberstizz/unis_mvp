package com.unis.util;

import java.security.SecureRandom;
import java.util.Random;

public class ReferralCodeGenerator {
    
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_SUFFIX_LENGTH = 5;
    private static final int MAX_PREFIX_LENGTH = 20;
    private static final Random RANDOM = new SecureRandom();

    public static String generate(String username) {
        String sanitized = username
                .toUpperCase()
                .replaceAll("[^A-Z0-9]", "");
        
        // C7 FIX: was Math.min(username.length(), 20) — must use sanitized.length()
        String prefix = sanitized.substring(0, Math.min(sanitized.length(), MAX_PREFIX_LENGTH));
        
        StringBuilder suffix = new StringBuilder(CODE_SUFFIX_LENGTH);
        for (int i = 0; i < CODE_SUFFIX_LENGTH; i++) {
            suffix.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        
        return prefix + "-" + suffix.toString();
    }

    public static String generateUnique(String username, java.util.function.Predicate<String> existsCheck) {
        String code;
        int attempts = 0;
        int maxAttempts = 10;
        
        do {
            code = generate(username);
            attempts++;
            
            if (attempts >= maxAttempts) {
                // L13 FIX: was timestamp-based (guessable) — now uses double-length random suffix
                String sanitized = username.toUpperCase().replaceAll("[^A-Z0-9]", "");
                String prefix = sanitized.substring(0, Math.min(sanitized.length(), MAX_PREFIX_LENGTH));
                StringBuilder fallbackSuffix = new StringBuilder(CODE_SUFFIX_LENGTH * 2);
                for (int i = 0; i < CODE_SUFFIX_LENGTH * 2; i++) {
                    fallbackSuffix.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
                }
                code = prefix + "-" + fallbackSuffix.toString();
                break;
            }
        } while (existsCheck.test(code));
        
        return code;
    }
}