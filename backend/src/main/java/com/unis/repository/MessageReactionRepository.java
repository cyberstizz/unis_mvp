package com.unis.repository;

import com.unis.entity.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, UUID> {

    List<MessageReaction> findByMessageIdInOrderByCreatedAtAsc(Collection<UUID> messageIds);

    List<MessageReaction> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    Optional<MessageReaction> findByMessageIdAndUserId(UUID messageId, UUID userId);
}
