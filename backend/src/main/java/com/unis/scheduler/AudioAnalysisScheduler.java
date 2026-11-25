package com.unis.scheduler;

import com.unis.entity.CronExecution;
import com.unis.service.AudioAnalysisService;
import com.unis.service.CronMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioAnalysisScheduler -- measures songs that haven't been measured yet:
 * everything uploaded before levelling existed, plus any upload whose
 * background measurement failed.
 *
 * Every 10 minutes it takes a small batch (newest songs first, so a fresh
 * upload that slipped through is fixed quickly) and measures them one at a
 * time. A song that fails 3 times is left alone; its error is in
 * songs.analysis_error.
 *
 * Settings (environment variables):
 *   UNIS_ANALYSIS_ENABLED=false   turns the job off
 *   UNIS_ANALYSIS_BATCH=12        songs per run (about 2-5 seconds each)
 *
 * Logged to cron_executions as AUDIO_ANALYSIS (awards_created = songs measured).
 */
@Component
public class AudioAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AudioAnalysisScheduler.class);

    private final JdbcTemplate jdbc;
    private final AudioAnalysisService analysis;
    private final CronMonitorService cronMonitorService;
    private final boolean enabled;
    private final int batch;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AudioAnalysisScheduler(JdbcTemplate jdbc,
                                  AudioAnalysisService analysis,
                                  CronMonitorService cronMonitorService,
                                  @Value("${unis.analysis.enabled:true}") boolean enabled,
                                  @Value("${unis.analysis.batch:12}") int batch) {
        this.jdbc = jdbc;
        this.analysis = analysis;
        this.cronMonitorService = cronMonitorService;
        this.enabled = enabled;
        this.batch = Math.max(1, Math.min(batch, 100));
    }

    @Scheduled(initialDelay = 180_000, fixedDelay = 600_000)
    public void catchUp() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        CronExecution exec = null;
        try {
            if (!analysis.ffmpegAvailable()) return;
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.queryForList("""
                    SELECT song_id, file_url FROM songs
                     WHERE analyzed_at IS NULL AND deleted_at IS NULL AND file_url IS NOT NULL
                       AND analysis_attempts < 3
                     ORDER BY created_at DESC
                     LIMIT ?
                    """, batch);
            } catch (Exception e) {
                log.error("[Analysis] action=catchup status=error err={} fix=run backend/sql/V2026_11g__playback_engine.sql", e.getMessage());
                return;
            }
            if (rows.isEmpty()) return;
            exec = cronMonitorService.startExecution("AUDIO_ANALYSIS");
            int ok = 0;
            for (Map<String, Object> row : rows) {
                if (analysis.analyzeStoredSong((UUID) row.get("song_id"), (String) row.get("file_url"))) ok++;
            }
            log.info("[Analysis] action=catchup status=ok candidates={} measured={}", rows.size(), ok);
            cronMonitorService.markSuccess(exec, ok);
        } catch (Exception e) {
            log.error("[Analysis] action=catchup status=error err={}", e.getMessage());
            if (exec != null) cronMonitorService.markFailed(exec, e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
