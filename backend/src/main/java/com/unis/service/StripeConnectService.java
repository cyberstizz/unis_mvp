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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
@Transactional
public class StripeConnectService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private EarningsService earningsService;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Logger log = LoggerFactory.getLogger(StripeConnectService.class);

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Value("${app.frontend.url:https://unisprototypetwo.netlify.app}")
    private String frontendBaseUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final BigDecimal PAYOUT_THRESHOLD = new BigDecimal("50.00");

    // Everyone's Stripe balance (song sales, support, Community Share
    // withdrawals) goes to their bank once a month, on this day. Stripe charges
    // Unis $2 for each account it pays out to in a month plus a fee per payout,
    // so one payout a month keeps that to a single charge per person. Unis
    // covers it.
    private static final int MONTHLY_PAYOUT_DAY = 1;

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

        // Monthly payouts. Not fatal: if Stripe refuses, the account still
        // works (on Stripe's default daily schedule) and the admin
        // "monthly payouts" button can apply it later.
        applyMonthlyPayoutSchedule(stripeAccountId);

        return stripeAccountId;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAYOUT SCHEDULE — once a month, on the 1st
    // ═══════════════════════════════════════════════════════════════════════════

    /** Sets one connected account to be paid out monthly. Returns null on success, or Stripe's error. */
    public String applyMonthlyPayoutSchedule(String stripeAccountId) {
        try {
            String formBody = "settings[payouts][schedule][interval]=monthly"
                    + "&settings[payouts][schedule][monthly_anchor]=" + MONTHLY_PAYOUT_DAY;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.stripe.com/v1/accounts/" + stripeAccountId))
                    .header("Authorization", "Bearer " + stripeSecretKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                String message = stripeErrorMessage(response.body());
                log.warn("[Payouts] action=monthly_schedule account={} status=error code={} err={}",
                        stripeAccountId, response.statusCode(), message);
                return message;
            }
            log.info("[Payouts] action=monthly_schedule account={} status=ok", stripeAccountId);
            return null;
        } catch (Exception e) {
            log.warn("[Payouts] action=monthly_schedule account={} status=error err={}", stripeAccountId, e.getMessage());
            return e.getMessage() == null ? "Couldn't reach Stripe." : e.getMessage();
        }
    }

    /** Applies the monthly schedule to every existing connected account (admin button). */
    public Map<String, Object> applyMonthlyPayoutScheduleToAll() {
        List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT username, stripe_account_id FROM users"
                + " WHERE stripe_account_id IS NOT NULL AND stripe_account_id <> '' AND deleted_at IS NULL"
                + " ORDER BY username");
        int updated = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            String error = applyMonthlyPayoutSchedule(String.valueOf(a.get("stripe_account_id")));
            if (error == null) {
                updated++;
            } else {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("username", a.get("username"));
                f.put("error", error);
                failures.add(f);
            }
        }
        log.info("[Payouts] action=monthly_schedule_all accounts={} updated={} failed={}",
                accounts.size(), updated, failures.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accounts", accounts.size());
        result.put("updated", updated);
        result.put("failed", failures.size());
        result.put("failures", failures);
        return result;
    }

    private String stripeErrorMessage(String body) {
        try {
            String msg = objectMapper.readTree(body).path("error").path("message").asText("");
            return msg.isBlank() ? "Stripe refused the change." : msg;
        } catch (Exception e) {
            return "Stripe refused the change.";
        }
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
     * A payout Stripe refused. It's a checked exception on purpose: Spring
     * commits the transaction for checked exceptions, so the failed payout and
     * its reason are kept in the person's payout history (a RuntimeException
     * would roll them back and they'd see nothing).
     */
    public static class PayoutException extends Exception {
        public PayoutException(String message) {
            super(message);
        }
    }

    /** How long a payout may sit unconfirmed before the next request checks Stripe for it. */
    private static final int UNCONFIRMED_MINUTES = 2;

    /**
     * Request a payout for the user: their whole available balance, $50 or more.
     *
     * Safe against double payouts:
     *   - The user's row is locked first, so a second request (another tab, a
     *     double tap, a retry) waits, then sees this payout and the new balance.
     *   - Stripe gets an idempotency key made from the payout's id, so the same
     *     payout can never become two transfers, even if it's sent twice.
     *   - If Stripe doesn't answer, the payout is returned as "processing"
     *     (which blocks a new one). The next request looks the transfer up in
     *     Stripe and either confirms it or sends it again with the same key.
     */
    public Payout requestPayout(UUID userId) throws Exception {
        jdbc.queryForList("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        if (user.getStripeAccountId() == null || !Boolean.TRUE.equals(user.getStripeOnboardingComplete())) {
            throw new RuntimeException("Please complete Stripe onboarding before requesting a payout.");
        }

        List<UUID> open = jdbc.queryForList("""
                SELECT payout_id FROM payouts
                WHERE user_id = ? AND status IN ('pending', 'processing')
                ORDER BY created_at DESC LIMIT 1
                """, UUID.class, userId);
        if (!open.isEmpty()) {
            Payout stuck = payoutRepository.findById(open.get(0)).orElse(null);
            if (stuck != null && stuck.getStripeTransferId() == null && stuck.getCreatedAt() != null
                    && stuck.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(UNCONFIRMED_MINUTES))) {
                return resumeUnconfirmed(stuck, user);
            }
            throw new RuntimeException("You already have a payout being processed.");
        }

        // Available = closed Community Share months + adjustments − payouts
        // already made from them (EarningsService reads the ledger).
        BigDecimal availableBalance = earningsService.getAvailableBalance(userId);
        if (availableBalance.compareTo(PAYOUT_THRESHOLD) < 0) {
            throw new RuntimeException("Minimum payout is $50.00. Current balance: $" + availableBalance.setScale(2, RoundingMode.HALF_UP));
        }

        BigDecimal payoutAmount = availableBalance.setScale(2, RoundingMode.FLOOR);

        Payout payout = Payout.builder()
                .user(user)
                .amount(payoutAmount)
                .status("processing")
                .periodStart(LocalDate.now().withDayOfMonth(1).minusMonths(1))
                .periodEnd(LocalDate.now().withDayOfMonth(1).minusDays(1))
                .createdAt(LocalDateTime.now())
                .build();
        payout = payoutRepository.saveAndFlush(payout);
        return sendTransfer(payout, user);
    }

    /** Sends (or re-sends, with the same idempotency key) the transfer for one payout. */
    private Payout sendTransfer(Payout payout, User user) throws PayoutException {
        long amountInCents = payout.getAmount().setScale(2, RoundingMode.FLOOR)
                .multiply(new BigDecimal("100")).longValueExact();
        String formBody = "amount=" + amountInCents
                + "&currency=usd"
                + "&destination=" + user.getStripeAccountId()
                + "&metadata[unis_payout_id]=" + payout.getPayoutId()
                + "&metadata[unis_user_id]=" + user.getUserId();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/transfers"))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + stripeSecretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", "unis-payout-" + payout.getPayoutId())
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= 2 && response == null; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[Payouts] action=transfer payout={} attempt={} status=no_answer err={}",
                        payout.getPayoutId(), attempt, e.getMessage());
            }
        }

        if (response == null) {
            // Unknown: Stripe may have made the transfer. Keep "processing" so no
            // second payout can start; the next request checks Stripe.
            payout.setFailureReason("No answer from Stripe yet; confirming.");
            payoutRepository.save(payout);
            log.error("[Payouts] action=transfer payout={} user={} amount={} status=unconfirmed",
                    payout.getPayoutId(), user.getUserId(), payout.getAmount());
            return payout;
        }

        String reason = null;
        String transferId = null;
        try {
            JsonNode json = objectMapper.readTree(response.body());
            if (response.statusCode() >= 300) reason = json.path("error").path("message").asText("Stripe returned " + response.statusCode());
            else transferId = json.path("id").asText(null);
        } catch (Exception e) {
            reason = "Unreadable answer from Stripe (" + response.statusCode() + ")";
        }

        if (transferId == null) {
            payout.setStatus("failed");
            payout.setFailureReason(reason);
            payoutRepository.save(payout);
            boolean ourSide = reason != null && reason.toLowerCase().contains("insufficient");
            log.error("[Payouts] action=transfer payout={} user={} amount={} status=failed ourSide={} err={}",
                    payout.getPayoutId(), user.getUserId(), payout.getAmount(), ourSide, reason);
            throw new PayoutException(ourSide
                    ? "Payouts are paused for a moment on our side. Your balance is safe; please try again later today."
                    : "Stripe couldn't send this payout. Check your account in the Stripe dashboard, then try again.");
        }

        payout.setStripeTransferId(transferId);
        payout.setStatus("completed");
        payout.setCompletedAt(LocalDateTime.now());
        payout.setFailureReason(null);
        payoutRepository.save(payout);
        log.info("[Payouts] action=transfer payout={} user={} amount={} transfer={} status=ok",
                payout.getPayoutId(), user.getUserId(), payout.getAmount(), transferId);
        return payout;
    }

    /**
     * A payout Stripe never answered for. Look for its transfer (they carry
     * unis_payout_id); if it's there, mark it completed, otherwise send it again
     * with the same idempotency key, which can't create a second transfer.
     */
    private Payout resumeUnconfirmed(Payout payout, User user) throws PayoutException {
        try {
            long since = payout.getCreatedAt().minusHours(1).atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
            HttpRequest list = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.stripe.com/v1/transfers?limit=100&destination="
                            + user.getStripeAccountId() + "&created%5Bgte%5D=" + since))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + stripeSecretKey)
                    .GET().build();
            HttpResponse<String> res = httpClient.send(list, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 300) {
                for (JsonNode t : objectMapper.readTree(res.body()).path("data")) {
                    if (payout.getPayoutId().toString().equals(t.path("metadata").path("unis_payout_id").asText())) {
                        payout.setStripeTransferId(t.path("id").asText());
                        payout.setStatus("completed");
                        payout.setCompletedAt(LocalDateTime.now());
                        payout.setFailureReason(null);
                        payoutRepository.save(payout);
                        log.info("[Payouts] action=resume payout={} status=found transfer={}", payout.getPayoutId(), t.path("id").asText());
                        return payout;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[Payouts] action=resume payout={} status=lookup_failed err={}", payout.getPayoutId(), e.getMessage());
        }
        log.info("[Payouts] action=resume payout={} status=resending", payout.getPayoutId());
        return sendTransfer(payout, user);
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