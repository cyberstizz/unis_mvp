package com.unis.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One gallery photo as the profile grid and the full-screen viewer see it.
 *
 * photoId / artistId / photoUrl / position / createdAt are the exact field
 * names the old ArtistPhoto entity serialized to, so the artist page, the
 * artist dashboard manager and the mobile app keep working unchanged.
 * likeCount and likedByMe are new (likedByMe is always false for guests).
 */
public record PhotoView(
        UUID photoId,
        UUID artistId,
        String photoUrl,
        Integer position,
        LocalDateTime createdAt,
        long likeCount,
        boolean likedByMe
) {}
