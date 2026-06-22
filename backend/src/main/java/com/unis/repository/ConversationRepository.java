package com.unis.repository;

import com.unis.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}