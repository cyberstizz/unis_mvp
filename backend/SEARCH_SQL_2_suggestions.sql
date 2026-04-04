-- ============================================================================
-- UNIS SEARCH — BLOCK 2 of 3: search_suggestions function
-- Paste this into Supabase SQL Editor and click Run.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.search_suggestions(
    query_text text,
    result_limit integer DEFAULT 10
)
RETURNS TABLE (
    id uuid,
    name text,
    subtitle text,
    type text,
    artwork_url text,
    score integer,
    similarity_score real
) AS $$
DECLARE
    normalized_query text := lower(trim(query_text));
    per_type_limit integer := GREATEST(3, result_limit / 3);
BEGIN
    IF length(normalized_query) < 2 THEN
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
            similarity(lower(u.username), normalized_query) AS similarity_score
        FROM public.users u
        LEFT JOIN public.genres g ON g.genre_id = u.genre_id
        WHERE u.role = 'artist'
          AND u.deleted_at IS NULL
          AND (
              lower(u.username) % normalized_query
              OR lower(u.username) ILIKE '%' || normalized_query || '%'
          )
        ORDER BY
            similarity(lower(u.username), normalized_query) DESC,
            u.score DESC
        LIMIT per_type_limit
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
            similarity(lower(s.title), normalized_query) AS similarity_score
        FROM public.songs s
        INNER JOIN public.users u ON u.user_id = s.artist_id
        WHERE s.deleted_at IS NULL
          AND (
              lower(s.title) % normalized_query
              OR lower(s.title) ILIKE '%' || normalized_query || '%'
          )
        ORDER BY
            similarity(lower(s.title), normalized_query) DESC,
            s.score DESC
        LIMIT per_type_limit
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
            similarity(lower(j.name), normalized_query) AS similarity_score
        FROM public.jurisdictions j
        WHERE j.voting_enabled = true
          AND (
              lower(j.name) % normalized_query
              OR lower(j.name) ILIKE '%' || normalized_query || '%'
          )
        ORDER BY
            similarity(lower(j.name), normalized_query) DESC,
            j.depth ASC
        LIMIT per_type_limit
    )

    ORDER BY similarity_score DESC
    LIMIT result_limit;
END;
$$ LANGUAGE plpgsql STABLE;