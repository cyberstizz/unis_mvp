-- ============================================================================
-- UNIS SEARCH — BLOCK 3 of 3: search_all + search_trending functions
-- Paste this into Supabase SQL Editor and click Run.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.search_all(
    query_text text,
    filter_type text DEFAULT 'all',
    filter_jurisdiction_id uuid DEFAULT NULL,
    result_limit integer DEFAULT 20,
    result_offset integer DEFAULT 0
)
RETURNS TABLE (
    id uuid,
    name text,
    subtitle text,
    type text,
    artwork_url text,
    score integer,
    extra_json jsonb,
    similarity_score real
) AS $$
DECLARE
    normalized_query text := lower(trim(query_text));
BEGIN
    IF length(normalized_query) < 1 THEN
        RETURN;
    END IF;

    RETURN QUERY

    -- ARTISTS
    (
        SELECT
            u.user_id AS id,
            u.username::text AS name,
            COALESCE(g.name, '')::text AS subtitle,
            'artist'::text AS type,
            u.photo_url::text AS artwork_url,
            u.score AS score,
            jsonb_build_object(
                'jurisdictionId', u.jurisdiction_id,
                'level', u.level,
                'genreId', u.genre_id,
                'bio', u.bio
            ) AS extra_json,
            similarity(lower(u.username), normalized_query) AS similarity_score
        FROM public.users u
        LEFT JOIN public.genres g ON g.genre_id = u.genre_id
        WHERE u.role = 'artist'
          AND u.deleted_at IS NULL
          AND (filter_type = 'all' OR filter_type = 'artist')
          AND (filter_jurisdiction_id IS NULL OR u.jurisdiction_id = filter_jurisdiction_id)
          AND (
              lower(u.username) % normalized_query
              OR lower(u.username) ILIKE '%' || normalized_query || '%'
          )
    )

    UNION ALL

    -- SONGS
    (
        SELECT
            s.song_id AS id,
            s.title::text AS name,
            u.username::text AS subtitle,
            'song'::text AS type,
            s.artwork_url::text AS artwork_url,
            s.score AS score,
            jsonb_build_object(
                'artistId', s.artist_id,
                'jurisdictionId', s.jurisdiction_id,
                'level', s.level,
                'genreId', s.genre_id,
                'duration', s.duration,
                'explicit', s.explicit
            ) AS extra_json,
            GREATEST(
                similarity(lower(s.title), normalized_query),
                similarity(lower(u.username), normalized_query) * 0.7
            ) AS similarity_score
        FROM public.songs s
        INNER JOIN public.users u ON u.user_id = s.artist_id
        WHERE s.deleted_at IS NULL
          AND (filter_type = 'all' OR filter_type = 'song')
          AND (filter_jurisdiction_id IS NULL OR s.jurisdiction_id = filter_jurisdiction_id)
          AND (
              lower(s.title) % normalized_query
              OR lower(s.title) ILIKE '%' || normalized_query || '%'
              OR lower(u.username) % normalized_query
              OR (s.search_vector @@ plainto_tsquery('english', normalized_query))
          )
    )

    UNION ALL

    -- JURISDICTIONS
    (
        SELECT
            j.jurisdiction_id AS id,
            j.name::text AS name,
            COALESCE(j.path, '')::text AS subtitle,
            'jurisdiction'::text AS type,
            j.symbol_url::text AS artwork_url,
            0 AS score,
            jsonb_build_object(
                'depth', j.depth,
                'parentId', j.parent_jurisdiction_id,
                'votingEnabled', j.voting_enabled,
                'bio', j.bio
            ) AS extra_json,
            similarity(lower(j.name), normalized_query) AS similarity_score
        FROM public.jurisdictions j
        WHERE (filter_type = 'all' OR filter_type = 'jurisdiction')
          AND (
              lower(j.name) % normalized_query
              OR lower(j.name) ILIKE '%' || normalized_query || '%'
          )
    )

    ORDER BY similarity_score DESC, score DESC
    LIMIT result_limit
    OFFSET result_offset;
END;
$$ LANGUAGE plpgsql STABLE;


-- ============================================================================
-- Trending songs for zero-state search
-- ============================================================================

CREATE OR REPLACE FUNCTION public.search_trending(
    p_jurisdiction_id uuid DEFAULT NULL,
    result_limit integer DEFAULT 5
)
RETURNS TABLE (
    id uuid,
    name text,
    subtitle text,
    type text,
    artwork_url text,
    score integer
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.song_id AS id,
        s.title::text AS name,
        u.username::text AS subtitle,
        'song'::text AS type,
        s.artwork_url::text AS artwork_url,
        s.score AS score
    FROM public.songs s
    INNER JOIN public.users u ON u.user_id = s.artist_id
    WHERE s.deleted_at IS NULL
      AND (p_jurisdiction_id IS NULL OR s.jurisdiction_id = p_jurisdiction_id)
    ORDER BY s.plays_today DESC, s.score DESC
    LIMIT result_limit;
END;
$$ LANGUAGE plpgsql STABLE;


-- ============================================================================
-- VERIFY: Run these after all 3 blocks complete successfully
-- ============================================================================
-- SELECT * FROM search_suggestions('har');
-- SELECT * FROM search_all('harlem', 'all', NULL, 20, 0);
-- SELECT * FROM search_trending(NULL, 5);