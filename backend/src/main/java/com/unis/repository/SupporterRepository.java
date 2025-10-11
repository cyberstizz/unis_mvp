package com.unis.repository;

import com.unis.entity.Supporter;
import com.unis.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface SupporterRepository extends JpaRepository<Supporter, UUID> {
    // Find by listener (e.g., for user dashboard)
    @Query("SELECT s FROM Supporter s WHERE s.listener.userId = :listenerId")
    Supporter findByListenerId(@Param("listenerId") UUID listenerId);

    // Count supporters for an artist (for score calcs: +5 each)
    @Query("SELECT COUNT(s) FROM Supporter s WHERE s.artist.userId = :artistId")
    Long countByArtistId(@Param("artistId") UUID artistId);

    // Find all supporters for an artist (e.g., for analytics)
    @Query("SELECT s FROM Supporter s WHERE s.artist.userId = :artistId")
    List<Supporter> findByArtistId(@Param("artistId") UUID artistId);

    // Check if listener supports artist (for validation)
    boolean existsByListenerAndArtist(User listener, User artist);
}