package com.unis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Service
public class EmailService {

    @Value("${resend.api.key:}")
    private String resendApiKey;

    @Value("${resend.from.email:noreply@charleslambjr.com}")
    private String fromEmail;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    /** True when a Resend API key is configured. Lets callers (e.g. the digest
     *  scheduler) skip cleanly in local dev rather than firing doomed requests. */
    public boolean isConfigured() {
        return resendApiKey != null && !resendApiKey.isBlank();
    }

    /**
     * Generic transactional send via Resend. Provider-agnostic from the
     * caller's view: the digest scheduler hands over a rendered email and
     * doesn't care that Resend is underneath. Moving to Amazon SES later
     * changes only the body of this method.
     *
     * Never throws -- failure is logged and returned as false so a batch
     * caller can keep going through the rest of its recipients.
     */
    public boolean sendTransactional(String toEmail, String subject, String htmlContent) {
        if (!isConfigured()) {
            System.out.println("[Email] action=send status=skip reason=not_configured to=" + toEmail);
            return false;
        }
        if (toEmail == null || toEmail.isBlank()) {
            System.out.println("[Email] action=send status=skip reason=no_recipient");
            return false;
        }

        String normalizedEmail = toEmail.toLowerCase();
        long startNs = System.nanoTime();
        try {
            Map<String, String> payload = Map.of(
                    "from", fromEmail,
                    "to", normalizedEmail,
                    "subject", subject,
                    "html", htmlContent
            );
            String jsonPayload = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + resendApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            int code = response.statusCode();

            if (code >= 200 && code < 300) {
                System.out.println("[Email] action=send status=ok to=" + normalizedEmail
                    + " httpStatus=" + code + " durationMs=" + ms);
                return true;
            }
            System.err.println("[Email] action=send status=fail to=" + normalizedEmail
                + " httpStatus=" + code + " durationMs=" + ms + " body=" + response.body());
            return false;
        } catch (Exception e) {
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            System.err.println("[Email] action=send status=error to=" + normalizedEmail
                + " durationMs=" + ms + " err=" + e.getMessage());
            return false;
        }
    }

    /**
     * Send a password reset email via Resend API.
     * Falls back to console logging if API key is not configured.
     */
    public void sendResetEmail(String toEmail, String username, String resetUrl) {
        // Normalize to lowercase -- Resend's test-mode restriction is case-sensitive
        String normalizedEmail = toEmail.toLowerCase();

        if (resendApiKey == null || resendApiKey.isBlank()) {
            System.out.println("=== PASSWORD RESET EMAIL (no Resend key configured) ===");
            System.out.println("To: " + normalizedEmail);
            System.out.println("URL: " + resetUrl);
            System.out.println("========================================================");
            return;
        }

        String htmlBody = buildResetEmailHtml(username, resetUrl);

        try {
            // Use Jackson to serialize -- eliminates all manual escaping bugs
            Map<String, String> payload = Map.of(
                    "from", fromEmail,
                    "to", normalizedEmail,
                    "subject", "Reset Your Unis Password",
                    "html", htmlBody
            );
            String jsonPayload = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + resendApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Always log status + body so failures are never silent
            System.out.println("Resend status: " + response.statusCode());
            System.out.println("Resend response: " + response.body());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("Password reset email sent to " + normalizedEmail);
            } else {
                System.err.println("Resend API error (" + response.statusCode() + "): " + response.body());
                System.out.println("FALLBACK RESET URL: " + resetUrl);
            }
        } catch (Exception e) {
            System.err.println("Failed to send reset email: " + e.getMessage());
            e.printStackTrace();
            System.out.println("FALLBACK RESET URL: " + resetUrl);
        }
    }

    private String buildResetEmailHtml(String username, String resetUrl) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="margin:0; padding:0; background-color:#0a0e1a; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
              <div style="max-width:520px; margin:40px auto; background:#111827; border-radius:16px; border:1px solid rgba(255,255,255,0.08); overflow:hidden;">

                <!-- Header -->
                <div style="background:linear-gradient(135deg,#163387,#0a0e1a); padding:40px 32px 32px; text-align:center;">
                  <img src="https://pub-fdce5bcbb7b14f3ead9299d58be5fbe6.r2.dev/unisLogoThree.svg"
                       alt="UNIS"
                       width="120"
                       style="display:block; margin:0 auto 16px; height:auto;" />
                  <p style="color:rgba(255,255,255,0.6); font-size:14px; margin:0;">Password Reset Request</p>
                </div>

                <!-- Body -->
                <div style="padding:32px;">
                  <p style="color:#d1d5db; font-size:16px; line-height:1.6; margin:0 0 24px;">
                    Hey <strong style="color:#ffffff;">%s</strong>, we received a request to reset your password.
                  </p>

                  <!-- Button -->
                  <div style="text-align:center; margin:32px 0;">
                    <a href="%s"
                       style="display:inline-block; background:#163387; color:#ffffff; padding:14px 40px;
                              border-radius:10px; text-decoration:none; font-weight:600; font-size:16px;">
                      Reset My Password
                    </a>
                  </div>

                  <p style="color:#9ca3af; font-size:14px; line-height:1.6; margin:0 0 16px;">
                    This link expires in <strong style="color:#d1d5db;">1 hour</strong>.
                    If you didn't request this, you can safely ignore this email.
                  </p>

                  <hr style="border:none; border-top:1px solid rgba(255,255,255,0.08); margin:24px 0;">

                  <p style="color:#6b7280; font-size:12px; margin:0;">
                    If the button doesn't work, copy and paste this link:<br>
                    <span style="color:#3b82f6; word-break:break-all;">%s</span>
                  </p>
                </div>

                <!-- Footer -->
                <div style="padding:20px 32px; background:rgba(0,0,0,0.3); text-align:center;">
                  <p style="color:#4b5563; font-size:12px; margin:0;">Unis Music Platform -- Your block's beats.</p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(username, resetUrl, resetUrl);
    }

    public void sendVerificationEmail(String toEmail, String username, String verifyUrl) {
        String normalizedEmail = toEmail.toLowerCase();
 
        if (resendApiKey == null || resendApiKey.isBlank()) {
            System.out.println("=== EMAIL VERIFICATION (no Resend key configured) ===");
            System.out.println("To: " + normalizedEmail);
            System.out.println("URL: " + verifyUrl);
            System.out.println("=====================================================");
            return;
        }
 
        String htmlBody = buildVerificationEmailHtml(username, verifyUrl);
 
        try {
            Map<String, String> payload = Map.of(
                    "from", fromEmail,
                    "to", normalizedEmail,
                    "subject", "Confirm your email to activate your Unis account",
                    "html", htmlBody
            );
            String jsonPayload = mapper.writeValueAsString(payload);
 
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + resendApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();
 
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Resend (verify) status: " + response.statusCode());
 
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Resend verify error (" + response.statusCode() + "): " + response.body());
                System.out.println("FALLBACK VERIFY URL: " + verifyUrl);
            } else {
                System.out.println("Verification email sent to " + normalizedEmail);
            }
        } catch (Exception e) {
            System.err.println("Failed to send verification email: " + e.getMessage());
            System.out.println("FALLBACK VERIFY URL: " + verifyUrl);
        }
    }
 
    private String buildVerificationEmailHtml(String username, String verifyUrl) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="margin:0; padding:0; background-color:#0a0e1a; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
              <div style="max-width:520px; margin:40px auto; background:#111827; border-radius:16px; border:1px solid rgba(255,255,255,0.08); overflow:hidden;">
 
                <div style="background:linear-gradient(135deg,#163387,#0a0e1a); padding:40px 32px 32px; text-align:center;">
                  <img src="https://pub-fdce5bcbb7b14f3ead9299d58be5fbe6.r2.dev/unisLogoThree.svg"
                       alt="UNIS" width="120" style="display:block; margin:0 auto 16px; height:auto;" />
                  <p style="color:rgba(255,255,255,0.6); font-size:14px; margin:0;">Confirm your email</p>
                </div>
 
                <div style="padding:32px;">
                  <p style="color:#d1d5db; font-size:16px; line-height:1.6; margin:0 0 24px;">
                    Welcome to Unis, <strong style="color:#ffffff;">%s</strong>. Confirm this email to activate your account and log in.
                  </p>
 
                  <div style="text-align:center; margin:32px 0;">
                    <a href="%s"
                       style="display:inline-block; background:#163387; color:#ffffff; padding:14px 40px;
                              border-radius:10px; text-decoration:none; font-weight:600; font-size:16px;">
                      Verify My Email
                    </a>
                  </div>
 
                  <p style="color:#9ca3af; font-size:14px; line-height:1.6; margin:0 0 16px;">
                    This link expires in <strong style="color:#d1d5db;">24 hours</strong>.
                    If you didn't create a Unis account, you can safely ignore this email.
                  </p>
 
                  <hr style="border:none; border-top:1px solid rgba(255,255,255,0.08); margin:24px 0;">
 
                  <p style="color:#6b7280; font-size:12px; margin:0;">
                    If the button doesn't work, copy and paste this link:<br>
                    <span style="color:#3b82f6; word-break:break-all;">%s</span>
                  </p>
                </div>
 
                <div style="padding:20px 32px; background:rgba(0,0,0,0.3); text-align:center;">
                  <p style="color:#4b5563; font-size:12px; margin:0;">Unis Music Platform -- Your block's beats.</p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(username, verifyUrl, verifyUrl);
    }
 
}