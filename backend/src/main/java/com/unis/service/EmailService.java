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

    /**
     * Send a password reset email via Resend API.
     * Falls back to console logging if API key is not configured.
     */
    public void sendResetEmail(String toEmail, String username, String resetUrl) {
        // Normalize to lowercase — Resend's test-mode restriction is case-sensitive
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
            // Use Jackson to serialize — eliminates all manual escaping bugs
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
                  <h1 style="color:#ffffff; font-size:28px; font-weight:700; margin:0 0 8px;">UNIS</h1>
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
                  <p style="color:#4b5563; font-size:12px; margin:0;">Unis Music Platform — Your block's beats.</p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(username, resetUrl, resetUrl);
    }
}