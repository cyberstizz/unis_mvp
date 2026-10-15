package com.unis.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one ranking formula on Unis.
 *
 * Award winners, live standings, voting-page nominees, the leaderboards page
 * and (in set form) territory rank all come from this definition:
 *
 * <pre>
 *   total points = weighted vote points + plays x 1 + likes x 5
 *   order        = total points, vote points, plays, likes, score, seniority
 * </pre>
 *
 * Vote weights by the interval the vote was cast for: Annual 250, Midterm 200,
 * Quarterly 60, Monthly 25, Weekly 20, Daily 10.
 *
 * <p><b>Whose activity counts, and on which day</b> (see RaceClockService):
 * <ul>
 *   <li>Votes count on the voter's own calendar day (votes.vote_date, which the
 *       server stamps on the voter's clock).</li>
 *   <li>Votes count in a race only when the voter lives in that race's
 *       jurisdiction or inside it. Local races are decided by local voters;
 *       the Unis-wide race is decided by everyone. A Los Angeles fan's
 *       Unis-wide vote for a Harlem artist counts Unis-wide, not in Harlem.</li>
 *   <li>Plays and likes count everywhere the artist competes, on the artist's
 *       home calendar day. Fans anywhere help an artist through listening.</li>
 * </ul>
 */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);

    public static final int PLAY_WEIGHT = 1;
    public static final int LIKE_WEIGHT = 5;

    public static final Map<String, Integer> VOTE_WEIGHTS = Map.of(
        "Annual", 250,
        "Midterm", 200,
        "Quarterly", 60,
        "Monthly", 25,
        "Weekly", 20,
        "Daily", 10
    );

    /** One ranked artist or song for a period. Immutable so it can be cached. */
    public record Candidate(
        UUID targetId,
        int rawVoteCount,
        int weightedPoints,
        int playsCount,
        int likesCount,
        int score,
        LocalDateTime seniority,
        int engagementPoints,
        int totalPoints,
        int totalCount          // how many candidates the whole race has (for paging)
    ) {}

    private final EntityManager entityManager;
    private final RaceClockService raceClock;

    public RankingService(EntityManager entityManager, RaceClockService raceClock) {
        this.entityManager = entityManager;
        this.raceClock = raceClock;
    }

    /**
     * Standings for display (nominees, leaderboards, live boards, the Charts
     * page). Cached for a minute in the "leaderboards" cache. Never used to
     * decide a winner. A null genreId ranks across all genres (Charts only —
     * awards are always per genre).
     */
    @Cacheable(value = "leaderboards",
        key = "'rank-' + #targetType + '-' + #raceJurisdictionId + '-' + #genreId + '-' "
            + "+ #startDate + '-' + #endDate + '-' + #limit + '-' + #offset")
    public List<Candidate> rankForDisplay(String targetType, UUID raceJurisdictionId, UUID genreId,
                                          LocalDate startDate, LocalDate endDate, int limit, int offset) {
        return rank(targetType, raceJurisdictionId, genreId, startDate, endDate, limit, offset);
    }

    /**
     * Rank every eligible candidate in one race for a period. Uncached — this
     * is what the award job decides winners with.
     *
     * Eligibility (unchanged from the award rules): an artist must live in the
     * race's jurisdiction or inside it; a song's home is its own jurisdiction,
     * falling back to its artist's (the same rule the voting wizard enforces).
     * A candidate qualifies for the genre by their own genre or by having
     * received a vote in it.
     */
    @SuppressWarnings("unchecked")
    public List<Candidate> rank(String targetType, UUID raceJurisdictionId, UUID genreId,
                                LocalDate startDate, LocalDate endDate, int limit, int offset) {
        List<UUID> thisAndChildren = subtreeOf(raceJurisdictionId);
        Set<UUID> related = new HashSet<>(thisAndChildren);
        related.addAll(ancestorsOf(raceJurisdictionId));

        // DELETION RULE: a race is judged as it stood when it closed. Accounts,
        // songs and votes removed before the close drop out; ones removed after
        // the close stay in that period's result. deleted_at is UTC wall time.
        LocalDateTime closesAt = RaceClockService.toUtcWall(raceClock.closesAt(raceJurisdictionId, endDate));
        LocalDateTime scanFrom = RaceClockService.scanFromUtc(startDate);
        LocalDateTime scanTo = RaceClockService.scanToUtc(endDate);

        String sql = "SELECT c.*, COUNT(*) OVER () AS total_count FROM ("
            + body(targetType, genreId) + ") c ORDER BY " + ORDER_BY
            + " LIMIT :limit OFFSET :offset";

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("related", new ArrayList<>(related));
        query.setParameter("thisAndChildren", thisAndChildren);
        if (genreId != null) query.setParameter("genreId", genreId);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);
        query.setParameter("scanFrom", scanFrom);
        query.setParameter("scanTo", scanTo);
        query.setParameter("closesAt", closesAt);
        query.setParameter("playWeight", PLAY_WEIGHT);
        query.setParameter("likeWeight", LIKE_WEIGHT);
        query.setParameter("limit", Math.max(1, limit));
        query.setParameter("offset", Math.max(0, offset));

        List<Object[]> rows = query.getResultList();
        List<Candidate> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new Candidate(
                (UUID) row[0],
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(),
                ((Number) row[4]).intValue(),
                ((Number) row[5]).intValue(),
                toLocalDateTime(row[6]),
                ((Number) row[7]).intValue(),
                ((Number) row[8]).intValue(),
                ((Number) row[9]).intValue()));
        }

        if (log.isDebugEnabled()) {
            log.debug("[Ranking] {} race={} genre={} {}..{} -> {} candidate(s)",
                targetType, raceJurisdictionId, genreId, startDate, endDate, out.size());
        }
        return List.copyOf(out);
    }

    /**
     * Where each of the given targets placed in a period, counting only
     * candidates that scored in it (a target that scored nothing is absent —
     * it was not on the chart). Used for the movement arrows on boards.
     */
    @SuppressWarnings("unchecked")
    public Map<UUID, Integer> positions(String targetType, UUID raceJurisdictionId, UUID genreId,
                                        LocalDate startDate, LocalDate endDate, List<UUID> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return Map.of();
        List<UUID> thisAndChildren = subtreeOf(raceJurisdictionId);
        Set<UUID> related = new HashSet<>(thisAndChildren);
        related.addAll(ancestorsOf(raceJurisdictionId));

        String sql = "SELECT r.target_id, r.rn FROM ("
            + "SELECT c.target_id, c.total_points, ROW_NUMBER() OVER (ORDER BY " + ORDER_BY + ") AS rn "
            + "FROM (" + body(targetType, genreId) + ") c"
            + ") r WHERE r.total_points > 0 AND r.target_id IN (:targetIds)";

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("related", new ArrayList<>(related));
        query.setParameter("thisAndChildren", thisAndChildren);
        if (genreId != null) query.setParameter("genreId", genreId);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);
        query.setParameter("scanFrom", RaceClockService.scanFromUtc(startDate));
        query.setParameter("scanTo", RaceClockService.scanToUtc(endDate));
        query.setParameter("closesAt",
            RaceClockService.toUtcWall(raceClock.closesAt(raceJurisdictionId, endDate)));
        query.setParameter("playWeight", PLAY_WEIGHT);
        query.setParameter("likeWeight", LIKE_WEIGHT);
        query.setParameter("targetIds", targetIds);

        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (Object[] row : (List<Object[]>) query.getResultList()) {
            out.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return out;
    }

    /** The candidate query for one race type, with or without a genre filter. */
    private static String body(String targetType, UUID genreId) {
        boolean byGenre = genreId != null;
        return ("song".equals(targetType) ? SONG_BODY : ARTIST_BODY)
            .replace("__VOTE_WEIGHT_CASE__", VOTE_WEIGHT_CASE)
            .replace("__PLAY_DAY__", artistDay("sp.played_at"))
            .replace("__LIKE_DAY__", artistDay("lk.created_at"))
            .replace("__VOTE_GENRE__", byGenre ? "AND v.genre_id = :genreId" : "")
            .replace("__SONG_GENRE__", byGenre ? "AND (s.genre_id = :genreId OR v.raw_vote_count IS NOT NULL)" : "")
            .replace("__ARTIST_GENRE__", byGenre ? "AND (u.genre_id = :genreId OR v.raw_vote_count IS NOT NULL)" : "");
    }

    /** Local calendar day of a UTC wall-clock timestamp, on the artist's clock (aj). */
    private static String artistDay(String column) {
        return "CAST((CAST(" + column + " AS timestamp) AT TIME ZONE 'UTC') AT TIME ZONE aj.timezone AS date)";
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return LocalDateTime.of(1970, 1, 1, 0, 0);
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toLocalDateTime();
        if (value instanceof java.time.Instant i) return RaceClockService.toUtcWall(i);
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    // =========================================================================
    // JURISDICTION HELPERS
    // =========================================================================

    /** This jurisdiction and everything inside it. */
    @SuppressWarnings("unchecked")
    public List<UUID> subtreeOf(UUID jurisdictionId) {
        Query q = entityManager.createNativeQuery("""
            WITH RECURSIVE tree AS (
                SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :id
                UNION ALL
                SELECT j.jurisdiction_id FROM jurisdictions j
                JOIN tree t ON j.parent_jurisdiction_id = t.jurisdiction_id
            )
            SELECT jurisdiction_id FROM tree
            """);
        q.setParameter("id", jurisdictionId);
        List<UUID> ids = q.getResultList();
        if (ids.isEmpty()) ids = List.of(jurisdictionId);
        return ids;
    }

    /** Every jurisdiction above this one. */
    @SuppressWarnings("unchecked")
    public List<UUID> ancestorsOf(UUID jurisdictionId) {
        Query q = entityManager.createNativeQuery("""
            WITH RECURSIVE up AS (
                SELECT parent_jurisdiction_id AS id FROM jurisdictions
                WHERE jurisdiction_id = :id AND parent_jurisdiction_id IS NOT NULL
                UNION ALL
                SELECT j.parent_jurisdiction_id FROM jurisdictions j
                JOIN up ON j.jurisdiction_id = up.id
                WHERE j.parent_jurisdiction_id IS NOT NULL
            )
            SELECT id FROM up
            """);
        q.setParameter("id", jurisdictionId);
        return q.getResultList();
    }

    // =========================================================================
    // SQL
    // =========================================================================

    private static final String VOTE_WEIGHT_CASE = """
        SUM(CASE
                WHEN vi.name = 'Annual'    THEN 250
                WHEN vi.name = 'Midterm'   THEN 200
                WHEN vi.name = 'Quarterly' THEN 60
                WHEN vi.name = 'Monthly'   THEN 25
                WHEN vi.name = 'Weekly'    THEN 20
                WHEN vi.name = 'Daily'     THEN 10
                ELSE 0
            END)""";

    /*
     * Votes: counted on vote_date (the voter's day), only from voters who live
     * in this race's jurisdiction or inside it, and only when cast in this
     * jurisdiction, one inside it, or one above it.
     *
     * Plays and likes: counted on the artist's day. scanFrom/scanTo narrow the
     * scan (index on played_at / created_at); the artist-day check is exact.
     */

    /** The tiebreaker order, shared by every ranking query. */
    private static final String ORDER_BY =
        "total_points DESC, weighted_points DESC, plays_count DESC, "
        + "likes_count DESC, score DESC, seniority ASC, target_id ASC";

    private static final String SONG_BODY = """
            SELECT
                s.song_id                                   AS target_id,
                COALESCE(v.raw_vote_count, 0)               AS raw_vote_count,
                COALESCE(v.weighted_points, 0)              AS weighted_points,
                COALESCE(p.plays_count, 0)                  AS plays_count,
                COALESCE(l.likes_count, 0)                  AS likes_count,
                COALESCE(s.score, 0)                        AS score,
                s.created_at                                AS seniority,
                (COALESCE(p.plays_count, 0) * :playWeight
                 + COALESCE(l.likes_count, 0) * :likeWeight) AS engagement_points,
                (COALESCE(v.weighted_points, 0)
                 + COALESCE(p.plays_count, 0) * :playWeight
                 + COALESCE(l.likes_count, 0) * :likeWeight) AS total_points
            FROM songs s
            JOIN users artist ON artist.user_id = s.artist_id
            LEFT JOIN (
                SELECT v.target_id,
                       COUNT(v.vote_id)        AS raw_vote_count,
                       __VOTE_WEIGHT_CASE__    AS weighted_points
                FROM votes v
                JOIN voting_intervals vi ON vi.interval_id = v.interval_id
                JOIN users voter ON voter.user_id = v.user_id
                WHERE v.target_type = 'song'
                  __VOTE_GENRE__
                  AND v.vote_date BETWEEN :startDate AND :endDate
                  AND v.jurisdiction_id IN (:related)
                  AND voter.jurisdiction_id IN (:thisAndChildren)
                  AND (voter.deleted_at IS NULL OR voter.deleted_at >= :closesAt)
                GROUP BY v.target_id
            ) v ON v.target_id = s.song_id
            LEFT JOIN (
                SELECT sp.song_id, COUNT(*) AS plays_count
                FROM song_plays sp
                JOIN songs ps ON ps.song_id = sp.song_id
                JOIN users pa ON pa.user_id = ps.artist_id
                JOIN jurisdictions aj ON aj.jurisdiction_id = pa.jurisdiction_id
                LEFT JOIN users listener ON listener.user_id = sp.user_id
                WHERE sp.played_at >= :scanFrom AND sp.played_at < :scanTo
                  AND __PLAY_DAY__ BETWEEN :startDate AND :endDate
                  AND COALESCE(ps.jurisdiction_id, pa.jurisdiction_id) IN (:thisAndChildren)
                  AND (listener.deleted_at IS NULL OR listener.deleted_at >= :closesAt)
                GROUP BY sp.song_id
            ) p ON p.song_id = s.song_id
            LEFT JOIN (
                SELECT lk.media_id, COUNT(*) AS likes_count
                FROM likes lk
                JOIN songs ls ON ls.song_id = lk.media_id
                JOIN users la ON la.user_id = ls.artist_id
                JOIN jurisdictions aj ON aj.jurisdiction_id = la.jurisdiction_id
                WHERE lk.media_type = 'song'
                  AND lk.created_at >= :scanFrom AND lk.created_at < :scanTo
                  AND __LIKE_DAY__ BETWEEN :startDate AND :endDate
                  AND COALESCE(ls.jurisdiction_id, la.jurisdiction_id) IN (:thisAndChildren)
                GROUP BY lk.media_id
            ) l ON l.media_id = s.song_id
            WHERE (s.deleted_at IS NULL OR s.deleted_at >= :closesAt)
              AND (artist.deleted_at IS NULL OR artist.deleted_at >= :closesAt)
              AND COALESCE(s.jurisdiction_id, artist.jurisdiction_id) IN (:thisAndChildren)
              __SONG_GENRE__
        """;

    private static final String ARTIST_BODY = """
            SELECT
                u.user_id                                   AS target_id,
                COALESCE(v.raw_vote_count, 0)               AS raw_vote_count,
                COALESCE(v.weighted_points, 0)              AS weighted_points,
                COALESCE(p.plays_count, 0)                  AS plays_count,
                COALESCE(l.likes_count, 0)                  AS likes_count,
                COALESCE(u.score, 0)                        AS score,
                u.created_at                                AS seniority,
                (COALESCE(p.plays_count, 0) * :playWeight
                 + COALESCE(l.likes_count, 0) * :likeWeight) AS engagement_points,
                (COALESCE(v.weighted_points, 0)
                 + COALESCE(p.plays_count, 0) * :playWeight
                 + COALESCE(l.likes_count, 0) * :likeWeight) AS total_points
            FROM users u
            LEFT JOIN (
                SELECT v.target_id,
                       COUNT(v.vote_id)        AS raw_vote_count,
                       __VOTE_WEIGHT_CASE__    AS weighted_points
                FROM votes v
                JOIN voting_intervals vi ON vi.interval_id = v.interval_id
                JOIN users voter ON voter.user_id = v.user_id
                WHERE v.target_type = 'artist'
                  __VOTE_GENRE__
                  AND v.vote_date BETWEEN :startDate AND :endDate
                  AND v.jurisdiction_id IN (:related)
                  AND voter.jurisdiction_id IN (:thisAndChildren)
                  AND (voter.deleted_at IS NULL OR voter.deleted_at >= :closesAt)
                GROUP BY v.target_id
            ) v ON v.target_id = u.user_id
            LEFT JOIN (
                SELECT ps.artist_id, COUNT(*) AS plays_count
                FROM song_plays sp
                JOIN songs ps ON ps.song_id = sp.song_id
                JOIN users pa ON pa.user_id = ps.artist_id
                JOIN jurisdictions aj ON aj.jurisdiction_id = pa.jurisdiction_id
                LEFT JOIN users listener ON listener.user_id = sp.user_id
                WHERE sp.played_at >= :scanFrom AND sp.played_at < :scanTo
                  AND __PLAY_DAY__ BETWEEN :startDate AND :endDate
                  AND pa.jurisdiction_id IN (:thisAndChildren)
                  AND (ps.deleted_at IS NULL OR ps.deleted_at >= :closesAt)
                  AND (listener.deleted_at IS NULL OR listener.deleted_at >= :closesAt)
                GROUP BY ps.artist_id
            ) p ON p.artist_id = u.user_id
            LEFT JOIN (
                SELECT ls.artist_id, COUNT(*) AS likes_count
                FROM likes lk
                JOIN songs ls ON ls.song_id = lk.media_id
                JOIN users la ON la.user_id = ls.artist_id
                JOIN jurisdictions aj ON aj.jurisdiction_id = la.jurisdiction_id
                WHERE lk.media_type = 'song'
                  AND lk.created_at >= :scanFrom AND lk.created_at < :scanTo
                  AND __LIKE_DAY__ BETWEEN :startDate AND :endDate
                  AND la.jurisdiction_id IN (:thisAndChildren)
                  AND (ls.deleted_at IS NULL OR ls.deleted_at >= :closesAt)
                GROUP BY ls.artist_id
            ) l ON l.artist_id = u.user_id
            WHERE u.role = 'artist'
              AND (u.deleted_at IS NULL OR u.deleted_at >= :closesAt)
              AND u.jurisdiction_id IN (:thisAndChildren)
              __ARTIST_GENRE__
        """;
}
