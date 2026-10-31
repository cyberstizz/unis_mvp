package com.unis.util;

import com.unis.entity.Jurisdiction;
import com.unis.entity.Song;
import com.unis.entity.User;
import org.hibernate.Hibernate;

import java.util.Collection;

/**
 * Loads the parts of an entity that its JSON includes, before the entity goes
 * into a cache.
 *
 * Some links (a user's jurisdiction and genre, a jurisdiction's parent) load
 * only when something first reads them. A cached entity is handed to many
 * requests; if one of those links is still unloaded, the first request to
 * write it as JSON loads it through the database session of the request that
 * cached it. That session may already be closed (an error, the request fails
 * with a 500) or still in use by that other request at the same moment. And if
 * the first response was cut off half-way, the cached copy stays half-loaded
 * until it expires.
 *
 * Call these at the end of a @Cacheable method, while its session is open.
 * hibernate.default_batch_fetch_size (application.yml) loads the links for a
 * whole list in a few batched queries rather than one per row.
 */
public final class CacheReady {

    private CacheReady() {}

    /** A user, with the default song shown on artist cards. */
    public static User user(User user) {
        if (user == null) return null;
        Hibernate.initialize(user.getJurisdiction());
        Hibernate.initialize(user.getGenre());
        Song defaultSong = user.getDefaultSong();
        if (defaultSong != null && defaultSong.getArtist() != user) {
            song(defaultSong);
        }
        return user;
    }

    public static <C extends Collection<User>> C users(C users) {
        if (users != null) users.forEach(CacheReady::user);
        return users;
    }

    /** A song and its artist. */
    public static Song song(Song song) {
        if (song == null) return null;
        Hibernate.initialize(song.getGenre());
        Hibernate.initialize(song.getJurisdiction());
        User artist = song.getArtist();
        if (artist != null) {
            Hibernate.initialize(artist);
            Hibernate.initialize(artist.getJurisdiction());
            Hibernate.initialize(artist.getGenre());
        }
        return song;
    }

    public static <C extends Collection<Song>> C songs(C songs) {
        if (songs != null) songs.forEach(CacheReady::song);
        return songs;
    }

    /** A jurisdiction and every parent above it (the JSON includes the whole chain). */
    public static Jurisdiction jurisdiction(Jurisdiction jurisdiction) {
        for (Jurisdiction j = jurisdiction; j != null; j = j.getParentJurisdiction()) {
            Hibernate.initialize(j);
        }
        return jurisdiction;
    }

    public static <C extends Collection<Jurisdiction>> C jurisdictions(C jurisdictions) {
        if (jurisdictions != null) jurisdictions.forEach(CacheReady::jurisdiction);
        return jurisdictions;
    }
}
