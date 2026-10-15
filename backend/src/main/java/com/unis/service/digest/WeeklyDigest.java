package com.unis.service.digest;

import java.util.List;
import java.util.UUID;

/**
 * Everything one recipient's Sunday email needs, already resolved: names,
 * absolute image URLs, ranks and links. Built by WeeklyDigestService, drawn
 * by WeeklyDigestRenderer. Plain data — no Spring, no database.
 */
public record WeeklyDigest(
    String recipientName,
    boolean recipientIsArtist,

    /** The reader's area: their home jurisdiction, or the one above it when that's where the activity is. */
    String areaName,
    /** Charts-page place key for the area ("uptown-harlem"); the page ignores unknown keys. */
    String areaPlaceKey,

    /** The week this email is about, e.g. "Oct 12 – 18". */
    String weekLabel,
    /** True when the reader's week ends tonight (the normal Sunday send). */
    boolean closesTonight,

    /** True when the area's boards show this week; false when nobody has scored yet and they show last week's final. */
    boolean areaLive,
    /** The same for the Unis-wide board, which can differ from the area's. */
    boolean unisLive,

    Pick topArtist,          // null when nobody has scored this week or last
    Pick topSong,            // null when no song has, or when it's from a different week than topArtist
    Pick unisArtist,         // #1 artist on all of Unis; null when nobody has scored or the area IS Unis
    List<Pick> topFive,      // the area's top artists that scored, up to five

    Personal personal,

    String appBaseUrl,
    String unsubscribeUrl,
    String postalAddress     // may be blank — then the line is left out
) {

    /** One ranked artist or song. imageUrl is absolute (https) or null. */
    public record Pick(
        UUID id,
        String type,         // "artist" | "song"
        int rank,
        String name,
        String artistName,   // song rows
        UUID artistId,       // song rows
        String imageUrl,
        int points,
        int votes,
        int plays,
        int likes,
        Integer movement,    // + up, - down, null = nothing to compare
        boolean isNew,
        Integer leadOverNext // points ahead of the next entry; null when nobody else scored
    ) {}

    /** The reader's own week. artistRank is null for listeners and for artists who haven't scored. */
    public record Personal(Integer artistRank, int playsThisWeek, int votesThisWeek) {}

    /** The area board shows this week and someone is on it. */
    public boolean live() {
        return areaLive && topArtist != null;
    }
}
