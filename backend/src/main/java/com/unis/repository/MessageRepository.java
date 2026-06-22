package com.unis.repository;

import com.unis.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    // Newest-first page of a thread (frontend reverses for display)
    List<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    // Cursor pagination: load older messages before a timestamp
    List<Message> findByConversationIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID conversationId, LocalDateTime before, Pageable pageable);

    // Unread = messages in this conversation NOT sent by the viewer, after their last read
    long countByConversationIdAndSenderIdNotAndCreatedAtAfter(
            UUID conversationId, UUID senderId, LocalDateTime after);

    // Read receipts: mark the other party's unread messages as read
    @Modifying
    @Query("UPDATE Message m SET m.readAt = :now " +
           "WHERE m.conversationId = :conversationId AND m.senderId <> :viewerId AND m.readAt IS NULL")
    int markOtherPartyRead(@Param("conversationId") UUID conversationId,
                           @Param("viewerId") UUID viewerId,
                           @Param("now") LocalDateTime now);
}