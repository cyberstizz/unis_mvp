package com.unis.scheduler;

import com.unis.service.AwardCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Winner emails: at five past every hour (four minutes after RaceCloseScheduler
 * closes the races that just ended), every new winner gets one email with a
 * link to their award page. UNIS_WINNER_EMAILS_ENABLED=false turns it off;
 * UNIS_WINNER_EMAIL_LIMIT caps sends per run (Resend's free plan allows 100
 * emails a day in total).
 */
@Component
public class WinnerNoticeScheduler {

    private static final Logger log = LoggerFactory.getLogger(WinnerNoticeScheduler.class);

    private final AwardCardService cards;
    private final boolean enabled;

    public WinnerNoticeScheduler(AwardCardService cards,
                                 @Value("${UNIS_WINNER_EMAILS_ENABLED:true}") boolean enabled) {
        this.cards = cards;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    public void notifyWinners() {
        if (!enabled) return;
        try {
            cards.notifyNewWinners();
        } catch (Exception e) {
            log.error("[WinnerEmail] action=run status=error err={}", e.getMessage());
        }
    }
}
