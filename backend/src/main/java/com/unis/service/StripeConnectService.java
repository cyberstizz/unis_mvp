package com.unis.service;

import com.unis.entity.Payout;
import com.unis.entity.User;
import com.unis.repository.PayoutRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional
public class StripeConnectService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private EarningsService earningsService;

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Value("${app.frontend.url:https://unisprototypetwo.netlify.app}")
    private String frontendBaseUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final BigDecimal PAYOUT_THRESHOLD = new BigDecimal("50.00");

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCOUNT CREATION — Create a Stripe Express connected account
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create a Stripe Express account for a Unis user.
     * Returns the Stripe account ID.
     */
    public String createConnectedAccount(UUID userId) throws Exception {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // If they already have an account, return it
        if (user.getStripeAccountId() != null && !user.getStripeAccountId().isBlank()) {
            return user.getStripeAccountId();
        }

        // Create Express account via Stripe API
        String formBody = "type=express"
                + "&country=US"
                + "&email=" + encodeValue(user.getEmail())
                + "&capabilities[transfers][requested]=true"
                + "&business_type=individual"
                + "&metadata[unis_user_id]=" + userId.toString()
                + "&metadata[unis_username]=" + encodeValue(user.getUsername());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/accounts"))
                .header("Authorization", "Bearer " + stripeSecretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("Stripe account creation failed: " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String stripeAccountId = json.get("id").asText();

        // Save to user record
        user.setStripeAccountId(stripeAccountId);
        user.setStripeOnboardingComplete(false);
        userRepository.save(user);

        return stripeAccountId;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ONBOARDING LINK — Generate Stripe-hosted onboarding URL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generate a Stripe Connect onboarding link for the user.
     * Redirects back to the Unis earnings page on completion.
     */
    public String createOnboardingLink(UUID userId) throws Exception {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        String stripeAccountId = user.getStripeAccountId();
        if (stripeAccountId == null || stripeAccountId.isBlank()) {
            // Auto-create if they don't have one yet
            stripeAccountId = createConnectedAccount(userId);
        }

        String refreshUrl = frontendBaseUrl + "/earnings?stripe=refresh";
        String returnUrl = frontendBaseUrl + "/earnings?stripe=complete";

        String formBody = "account=" + stripeAccountId
                + "&refresh_url=" + encodeValue(refreshUrl)
                + "&return_url=" + encodeValue(returnUrl)
                + "&type=account_onboarding";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/account_links"))
                .header("Authorization", "Bearer " + stripeSecretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("Stripe onboarding link creation failed: " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("url").asText();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCOUNT STATUS — Check if onboarding is complete
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check the Stripe account status and update onboarding flag.
     * Returns a map with status details.
     */
    public Map<String, Object> getAccountStatus(UUID userId) throws Exception {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        Map<String, Object> status = new HashMap<>();

        if (user.getStripeAccountId() == null || user.getStripeAccountId().isBlank()) {
            status.put("hasAccount", false);
            status.put("onboardingComplete", false);
            status.put("payoutsEnabled", false);
            return status;
        }

        // Fetch account from Stripe
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/accounts/" + user.getStripeAccountId()))
                .header("Authorization", "Bearer " + stripeSecretKey)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("Stripe account fetch failed: " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());

        boolean detailsSubmitted = json.has("details_submitted") && json.get("details_submitted").asBoolean();
        boolean payoutsEnabled = json.has("payouts_enabled") && json.get("payouts_enabled").asBoolean();
        boolean chargesEnabled = json.has("charges_enabled") && json.get("charges_enabled").asBoolean();

        // Update our records if onboarding completed
        if (detailsSubmitted && !user.getStripeOnboardingComplete()) {
            user.setStripeOnboardingComplete(true);
            userRepository.save(user);
        }

        status.put("hasAccount", true);
        status.put("onboardingComplete", detailsSubmitted);
        status.put("payoutsEnabled", payoutsEnabled);
        status.put("chargesEnabled", chargesEnabled);
        status.put("stripeAccountId", user.getStripeAccountId());

        return status;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAYOUT — Transfer money to a connected account
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Request a payout for the user.
     * Validates balance >= $50, no active payouts, and Stripe account is ready.
     */
    public Payout requestPayout(UUID userId) throws Exception {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Validate Stripe account
        if (user.getStripeAccountId() == null || !user.getStripeOnboardingComplete()) {
            throw new RuntimeException("Please complete Stripe onboarding before requesting a payout.");
        }

        // Check for active payouts
        if (payoutRepository.hasActivePayout(userId)) {
            throw new RuntimeException("You already have a payout being processed.");
        }

        // Calculate available balance
        Map<String, Object> summary = earningsService.getEarningsSummary(userId);
        BigDecimal totalEarnings = new BigDecimal(summary.get("currentBalance").toString());
        BigDecimal completedPayouts = payoutRepository.sumCompletedPayouts(userId);
        BigDecimal availableBalance = totalEarnings.subtract(completedPayouts);

        if (availableBalance.compareTo(PAYOUT_THRESHOLD) < 0) {
            throw new RuntimeException("Minimum payout is $50.00. Current balance: $" + availableBalance.setScale(2, RoundingMode.HALF_UP));
        }

        // Round to 2 decimal places for transfer
        BigDecimal payoutAmount = availableBalance.setScale(2, RoundingMode.FLOOR);
        long amountInCents = payoutAmount.multiply(new BigDecimal("100")).longValue();

        // Create the payout record first (status: processing)
        Payout payout = Payout.builder()
                .user(user)
                .amount(payoutAmount)
                .status("processing")
                .periodStart(LocalDate.now().withDayOfMonth(1).minusMonths(1))
                .periodEnd(LocalDate.now().withDayOfMonth(1).minusDays(1))
                .createdAt(LocalDateTime.now())
                .build();
        payout = payoutRepository.save(payout);

        try {
            // Create Stripe Transfer
            String formBody = "amount=" + amountInCents
                    + "&currency=usd"
                    + "&destination=" + user.getStripeAccountId()
                    + "&metadata[unis_payout_id]=" + payout.getPayoutId().toString()
                    + "&metadata[unis_user_id]=" + userId.toString();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.stripe.com/v1/transfers"))
                    .header("Authorization", "Bearer " + stripeSecretKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 300) {
                throw new RuntimeException("Stripe transfer failed: " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String transferId = json.get("id").asText();

            // Update payout record
            payout.setStripeTransferId(transferId);
            payout.setStatus("completed");
            payout.setCompletedAt(LocalDateTime.now());
            payoutRepository.save(payout);

            return payout;

        } catch (Exception e) {
            // Mark payout as failed
            payout.setStatus("failed");
            payout.setFailureReason(e.getMessage());
            payoutRepository.save(payout);
            throw e;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAYOUT HISTORY
    // ═══════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getPayoutHistory(UUID userId) {
        List<Payout> payouts = payoutRepository.findByUser_UserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Payout p : payouts) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("payoutId", p.getPayoutId());
            entry.put("amount", p.getAmount());
            entry.put("status", p.getStatus());
            entry.put("periodStart", p.getPeriodStart().toString());
            entry.put("periodEnd", p.getPeriodEnd().toString());
            entry.put("createdAt", p.getCreatedAt().toString());
            entry.put("completedAt", p.getCompletedAt() != null ? p.getCompletedAt().toString() : null);
            entry.put("failureReason", p.getFailureReason());
            result.add(entry);
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STRIPE DASHBOARD LINK — Let users access their Stripe Express dashboard
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generate a login link to the Stripe Express dashboard
     * where users can view their payout schedule and bank details.
     */
    public String createDashboardLink(UUID userId) throws Exception {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        if (user.getStripeAccountId() == null) {
            throw new RuntimeException("No Stripe account found.");
        }

        String formBody = "account=" + user.getStripeAccountId();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/accounts/" + user.getStripeAccountId() + "/login_links"))
                .header("Authorization", "Bearer " + stripeSecretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("Stripe dashboard link failed: " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("url").asText();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private String encodeValue(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}