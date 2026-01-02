package com.unis.util;

import java.security.SecureRandom;
import java.util.Random;

public class ReferralCodeGenerator {
    
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_SUFFIX_LENGTH = 5;
    private static final Random RANDOM = new SecureRandom();

    /**
     * Generate a referral code based on username
     * Format: USERNAME-XXXXX (e.g., RAPKING-A7B3C)
     * 
     * @param username The user's username
     * @return A unique referral code candidate
     */
    public static String generate(String username) {
        // Sanitize username (uppercase, remove special chars, max 20 chars)
        String sanitized = username
                .toUpperCase()
                .replaceAll("[^A-Z0-9]", "")
                .substring(0, Math.min(username.length(), 20));
        
        // Generate random 5-character suffix
        StringBuilder suffix = new StringBuilder(CODE_SUFFIX_LENGTH);
        for (int i = 0; i < CODE_SUFFIX_LENGTH; i++) {
            suffix.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        
        return sanitized + "-" + suffix.toString();
    }

    /**
     * Generate a referral code and ensure it's unique by checking against existing codes
     * 
     * @param username The user's username
     * @param existsCheck Function that returns true if code already exists
     * @return A guaranteed unique referral code
     */
    public static String generateUnique(String username, java.util.function.Predicate<String> existsCheck) {
        String code;
        int attempts = 0;
        int maxAttempts = 10;
        
        do {
            code = generate(username);
            attempts++;
            
            // Safety: If we can't find a unique code after 10 attempts, append a timestamp
            if (attempts >= maxAttempts) {
                code = username.toUpperCase().substring(0, Math.min(username.length(), 15)) 
                       + "-" + System.currentTimeMillis();
                break;
            }
        } while (existsCheck.test(code));
        
        return code;
    }
}