package com.unis.service;

import com.unis.dto.MessageView;
import com.unis.entity.Conversation;
import com.unis.entity.Message;
import com.unis.entity.Supporter;
import com.unis.repository.ConversationRepository;
import com.unis.repository.MessageRepository;
import com.unis.repository.SupporterRepository;
import com.unis.repository.UserBlockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Artist → supporters broadcast.
 *
 * Sends one message to every supporter of the artist as a normal direct
 * message (a conversation is created per supporter if needed), so the fan can
 * reply and the artist can follow up 1:1 — the SoundCloud "message your fans"
 * model. Self-contained on purpose: it reuses the existing Conversation/Message
 * tables and the same STOMP push, without touching MessagingService.
 *
 * Supporters opted in by supporting, so the normal first-contact gate is
 * intentionally bypassed here. Blocks (either direction) are still respected,
 * and the artist's own id is skipped.
 */
@Service
@Slf4j
public class ArtistBroadcastService {

    private static final int PREVIEW_MAX = 120;

    private final SupporterRepository supporterRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserBlockRepository userBlockRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public ArtistBroadcastService(SupporterRepository supporterRepository,
                                  ConversationRepository conversationRepository,
                                  MessageRepository messageRepository,
                                  UserBlockRepository userBlockRepository,
                                  SimpMessagingTemplate messagingTemplate) {
        this.supporterRepository = supporterRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userBlockRepository = userBlockRepository;
        this.messagingTemplate = messagingTemplate;
    }

    public Map<String, Object> broadcastToSupporters(UUID artistId, String body) {
        String clean = body == null ? null : body.trim();
        if (clean == null || clean.isEmpty()) {
            throw new RuntimeException("Broadcast message can't be empty.");
        }

        List<Supporter> supporters = supporterRepository.findByArtistId(artistId);
        int sent = 0;
        int skipped = 0;

        for (Supporter sup : supporters) {
            UUID listenerId = (sup.getListener() != null) ? sup.getListener().getUserId() : null;
            if (listenerId == null || listenerId.equals(artistId)) {
                skipped++;
                continue;
            }
            if (userBlockRepository.existsByBlockerIdAndBlockedId(listenerId, artistId)
                    || userBlockRepository.existsByBlockerIdAndBlockedId(artistId, listenerId)) {
                skipped++;
                continue;
            }
            try {
                deliver(artistId, listenerId, clean);
                sent++;
            } catch (Exception e) {
                log.warn("Broadcast to {} failed: {}", listenerId, e.getMessage());
                skipped++;
            }
        }

        log.info("Broadcast by artist {}: sent={} skipped={} total={}",
                artistId, sent, skipped, supporters.size());
        return Map.of("sent", sent, "skipped", skipped, "total", supporters.size());
    }

    private void deliver(UUID artistId, UUID listenerId, String body) {
        UUID one = artistId.compareTo(listenerId) <= 0 ? artistId : listenerId;
        UUID two = artistId.compareTo(listenerId) <= 0 ? listenerId : artistId;

        Conversation convo = conversationRepository
                .findByParticipantOneAndParticipantTwo(one, two)
                .orElseGet(() -> conversationRepository.save(Conversation.builder()
                        .participantOne(one)
                        .participantTwo(two)
                        .createdAt(LocalDateTime.now())
                        .lastMessageAt(LocalDateTime.now())
                        .build()));

        LocalDateTime now = LocalDateTime.now();
        Message m = messageRepository.save(Message.builder()
                .conversationId(convo.getId())
                .senderId(artistId)
                .body(body)
                .createdAt(now)
                .build());

        convo.setLastMessageAt(now);
        convo.setLastMessagePreview(body.length() > PREVIEW_MAX ? body.substring(0, PREVIEW_MAX) : body);
        if (artistId.equals(convo.getParticipantOne())) {
            convo.setLastReadAtOne(now);
        } else {
            convo.setLastReadAtTwo(now);
        }
        conversationRepository.save(convo);

        try {
            messagingTemplate.convertAndSendToUser(listenerId.toString(), "/queue/messages", MessageView.from(m));
        } catch (Exception e) {
            log.warn("STOMP push (broadcast) to {} failed: {}", listenerId, e.getMessage());
        }
    }
}