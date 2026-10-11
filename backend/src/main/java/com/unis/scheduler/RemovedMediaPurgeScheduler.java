package com.unis.scheduler;

import com.unis.entity.CronExecution;
import com.unis.service.CronMonitorService;
import com.unis.service.FileStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deletes the files of songs removed more than 30 days ago.
 *
 * When a song is removed (artist, account deletion, DMCA, admin), its audio
 * and artwork are moved to a hidden location right away so nothing can play
 * them (MediaService.removeSong). They are kept for 30 days so a mistake or a
 * successful DMCA counter-notice can be undone: clearing the song's
 * deleted_at brings it back with its files. After 30 days this job deletes the
 * files for good and clears the song's file columns. The song row itself stays
 * forever, so award history keeps its title and artist.
 *
 * Runs 03:40 New York time nightly. Works through at most 500 songs a night;
 * anything left over is picked up the next night. A file that fails to delete
 * keeps its URL and is retried the next night.
 */
@Component
public class RemovedMediaPurgeScheduler {

    static final int HOLD_DAYS = 30;
    private static final int BATCH = 500;

    private final JdbcTemplate jdbcTemplate;
    private final FileStorageService fileStorageService;
    private final CronMonitorService cronMonitorService;

    public RemovedMediaPurgeScheduler(JdbcTemplate jdbcTemplate,
                                      FileStorageService fileStorageService,
                                      CronMonitorService cronMonitorService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fileStorageService = fileStorageService;
        this.cronMonitorService = cronMonitorService;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "America/New_York")
    public void purgeRemovedSongFiles() {
        CronExecution exec = cronMonitorService.startExecution("REMOVED_MEDIA_PURGE");
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(HOLD_DAYS);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT song_id, file_url, artwork_url
                  FROM songs
                 WHERE deleted_at IS NOT NULL
                   AND deleted_at < ?
                   AND (file_url IS NOT NULL OR artwork_url IS NOT NULL)
                 ORDER BY deleted_at
                 LIMIT ?
                """, cutoff, BATCH);

            int purged = 0;
            int failed = 0;
            for (Map<String, Object> row : rows) {
                UUID songId = (UUID) row.get("song_id");
                boolean ok = delete((String) row.get("file_url"), songId)
                           & delete((String) row.get("artwork_url"), songId);
                if (ok) {
                    jdbcTemplate.update(
                        "UPDATE songs SET file_url = NULL, artwork_url = NULL WHERE song_id = ? AND deleted_at IS NOT NULL",
                        songId);
                    purged++;
                } else {
                    failed++;
                }
            }

            System.out.println("[RemovedMediaPurge] action=run status=ok candidates=" + rows.size()
                + " purged=" + purged + " failed=" + failed);
            cronMonitorService.markSuccess(exec, purged);
        } catch (Exception e) {
            System.err.println("[RemovedMediaPurge] action=run status=error err=" + e.getMessage());
            cronMonitorService.markFailed(exec, e.getMessage());
        }
    }

    /** Deletes one file. Returns false (and logs) if storage refused, so the song is retried tomorrow. */
    private boolean delete(String url, UUID songId) {
        if (url == null) return true;
        try {
            fileStorageService.deleteFile(url);
            return true;
        } catch (Exception e) {
            System.err.println("[RemovedMediaPurge] action=delete_file status=error songId=" + songId
                + " url=" + url + " err=" + e.getMessage());
            return false;
        }
    }
}
