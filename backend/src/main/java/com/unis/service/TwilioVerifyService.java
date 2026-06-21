package com.unis.service;

import com.twilio.Twilio;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper over Twilio Verify v2. Twilio owns the OTP lifecycle (generation,
 * SMS delivery, rate limiting, expiry, retries) — we only ask it to start a
 * verification and to check a code. Nothing is stored locally except the
 * phone number on the user row.
 *
 * Config (env vars wired in application.yml):
 *   twilio.account-sid        ACxxxx…
 *   twilio.auth-token         (secret)
 *   twilio.verify-service-sid VAxxxx… (from Console → Verify → Services)
 */
@Service
public class TwilioVerifyService {

    @Value("${twilio.account-sid:}")        private String accountSid;
    @Value("${twilio.auth-token:}")         private String authToken;
    @Value("${twilio.verify-service-sid:}") private String verifyServiceSid;

    private volatile boolean initialized = false;

    private synchronized void ensureInit() {
        if (initialized) return;
        if (accountSid == null || accountSid.isBlank()
                || authToken == null || authToken.isBlank()
                || verifyServiceSid == null || verifyServiceSid.isBlank()) {
            throw new IllegalStateException(
                "Twilio is not configured. Set twilio.account-sid, twilio.auth-token, and twilio.verify-service-sid.");
        }
        Twilio.init(accountSid, authToken);
        initialized = true;
    }

    /** Send a fresh code to the given E.164 number over SMS. */
    public void startVerification(String phoneE164) {
        ensureInit();
        Verification.creator(verifyServiceSid, phoneE164, "sms").create();
    }

    /** Returns true only when Twilio reports the code as "approved". */
    public boolean checkVerification(String phoneE164, String code) {
        ensureInit();
        VerificationCheck check = VerificationCheck.creator(verifyServiceSid)
                .setTo(phoneE164)
                .setCode(code)
                .create();
        return "approved".equalsIgnoreCase(check.getStatus());
    }
}