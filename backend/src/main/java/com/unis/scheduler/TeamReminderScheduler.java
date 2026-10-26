package com.unis.scheduler;

import com.unis.service.EmailService;
import com.unis.service.StreetTeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Street team reminders: at quarter past every hour, everyone going to a
 * meetup that starts in 20 to 28 hours gets one reminder email. Each meetup is
 * reminded once (team_meetups.reminded_at), so overlapping runs or a second
 * server instance can't send it twice.
 */
@Component
public class TeamReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TeamReminderScheduler.class);

    private final StreetTeamService team;
    private final EmailService email;

    public TeamReminderScheduler(StreetTeamService team, EmailService email) {
        this.team = team;
        this.email = email;
    }

    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
    public void remind() {
        if (!email.isConfigured()) return;
        try {
            team.sendReminders();
        } catch (Exception e) {
            log.error("[StreetTeam] action=reminders status=error err={}", e.getMessage());
        }
    }
}
