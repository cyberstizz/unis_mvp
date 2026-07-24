package com.unis.dto;

/**
 * One row of an artist's award tally.
 *
 * <p>Shape deliberately matches what {@code artistpage.jsx}'s
 * {@code normalizeAwards()} already parses, so the frontend needs no
 * translation layer:
 *
 * <pre>
 *   [ { "entity": "artist", "interval": "daily",   "count": 3 },
 *     { "entity": "song",   "interval": "weekly",  "count": 1 } ]
 * </pre>
 *
 * <p><b>No schema change backs this.</b> Every award win is already one row in
 * the {@code awards} table (unique on target_type + target_id + jurisdiction_id
 * + interval_id + award_date), so the tally is a plain GROUP BY aggregate.
 * Denormalized per-award counter columns were considered and rejected: they
 * drift from the source table, need a backfill, and would require a schema
 * migration every time a new voting interval is introduced.
 *
 * <p>{@code interval} is lowercased to match the frontend's AWARD_DEFS keys
 * ({@code artist-daily}, {@code song-quarterly}, …). Live interval names are
 * Daily, Weekly, Midterm, Monthly, Quarterly, Annual.
 */
public class AwardTallyDto {

    private String entity;    // "artist" | "song"
    private String interval;  // "daily" | "weekly" | "midterm" | "monthly" | "quarterly" | "annual"
    private long count;

    public AwardTallyDto() {
    }

    /**
     * Constructor used by the JPQL constructor expressions in
     * {@code AwardTallyRepository}. COUNT(...) yields a Long, hence the boxed
     * parameter.
     */
    public AwardTallyDto(String entity, String interval, Long count) {
        this.entity = entity;
        this.interval = interval;
        this.count = count == null ? 0L : count;
    }

    public String getEntity() {
        return entity;
    }

    public void setEntity(String entity) {
        this.entity = entity;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}