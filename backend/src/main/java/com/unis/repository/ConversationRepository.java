package com.unis.repository;

import com.unis.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByParticipantOneAndParticipantTwo(UUID participantOne, UUID participantTwo);

    @Query("SELECT c FROM Conversation c " +
           "WHERE (c.participantOne = :userId OR c.participantTwo = :userId) " +
           "ORDER BY c.lastMessageAt DESC NULLS LAST")
    List<Conversation> findAllForUser(@Param("userId") UUID userId);

    // Daily new-people limit: conversations this sender started as requests since `since`.
    @Query("SELECT COUNT(c) FROM Conversation c " +
           "WHERE c.initiatedBy = :senderId AND c.startedAsRequest = true AND c.createdAt > :since")
    long countRequestsStartedSince(@Param("senderId") UUID senderId, @Param("since") LocalDateTime since);

    // Presence fan-out: everyone this user has an accepted conversation with.
    @Query("SELECT CASE WHEN c.participantOne = :userId THEN c.participantTwo ELSE c.participantOne END " +
           "FROM Conversation c " +
           "WHERE (c.participantOne = :userId OR c.participantTwo = :userId) " +
           "AND c.status = com.unis.entity.ConversationStatus.ACCEPTED")
    List<UUID> findAcceptedPartnerIds(@Param("userId") UUID userId);
}
