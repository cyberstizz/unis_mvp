package com.unis.service;

import com.unis.dto.ConversationView;
import com.unis.dto.MessageView;
import com.unis.entity.Conversation;
import com.unis.entity.Message;
import com.unis.entity.ModerationAction;
import com.unis.entity.User;
import com.unis.entity.UserBlock;
import com.unis.repository.ConversationRepository;
import com.unis.repository.FollowRepository;
import com.unis.repository.MessageRepository;
import com.unis.repository.ModerationActionRepository;
import com.unis.repository.UserBlockRepository;
import com.unis.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * All direct-messaging logic: conversation resolution, the can-message gate,
 * send (persist + real-time push), read receipts, inbox listing, and the
 * block/report safety path.
 *
 * Transport split: messages are SENT over REST (reliable, transactional) and
 * RECEIVED in real time over STOMP. After persisting, we push the saved
 * MessageView to both participants' /user/queue/messages destinations.
 */
@Service
@Slf4j
public class MessagingService {

    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final int PREVIEW_MAX = 120;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserBlockRepository userBlockRepository;
    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final ModerationActionRepository moderationActionRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            UserBlockRepository userBlockRepository,
                            FollowRepository followRepository,
                            UserRepository userRepository,
                            ModerationActionRepository moderationActionRepository,
                            SimpMessagingTemplate messagingTemplate) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userBlockRepository = userBlockRepository;
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.moderationActionRepository = moderationActionRepository;
        this.messagingTemplate = messagingTemplate;
    }

    // ═══════════════════════════════════════════════════════════
    // SEND
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public MessageView sendMessage(UUID senderId, UUID recipientId, String body,
                                   UUID sharedSongId, Long supportPaymentId, String source) {

        String cleanBody = body == null ? null : body.trim();
        boolean hasContent = (cleanBody != null && !cleanBody.isEmpty())
                || sharedSongId != null || supportPaymentId != null;
        if (!hasContent) {
            throw new RuntimeException("Message can't be empty.");
        }

        // Per-message guards (apply even on existing conversations)
        assertCanSend(senderId, recipientId);

        // Resolve or create the conversation. The full open/followers gate is
        // enforced only when a NEW conversation is created (first contact).
        Conversation convo = getOrCreateConversation(senderId, recipientId);

        Message message = messageRepository.save(Message.builder()
                .conversationId(convo.getId())
                .senderId(senderId)
                .body(cleanBody)
                .sharedSongId(sharedSongId)
                .supportPaymentId(supportPaymentId)
                .createdAt(LocalDateTime.now())
                .build());

        // Update conversation metadata; sender has implicitly read their own message
        LocalDateTime now = message.getCreatedAt();
        convo.setLastMessageAt(now);
        convo.setLastMessagePreview(buildPreview(cleanBody, sharedSongId, supportPaymentId));
        if (senderId.equals(convo.getParticipantOne())) {
            convo.setLastReadAtOne(now);
        } else {
            convo.setLastReadAtTwo(now);
        }
        conversationRepository.save(convo);

        MessageView view = MessageView.from(message);

        // Real-time fan-out to both participants (recipient + sender's other devices)
        pushToUser(recipientId, view);
        pushToUser(senderId, view);

        log.info("Message {} sent in conversation {} ({} -> {})",
                message.getId(), convo.getId(), senderId, recipientId);
        return view;
    }

    private void pushToUser(UUID userId, MessageView view) {
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/messages", view);
        } catch (Exception e) {
            // Never let a transient WS failure roll back a persisted message.
            log.warn("STOMP push to {} failed: {}", userId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CONVERSATIONS
    // ═══════════════════════════════════════════════════════════

    /** Used by the "Message" button on a profile — creates the thread if needed. */
    @Transactional
    public ConversationView startConversation(UUID initiatorId, UUID otherId) {
        assertCanSend(initiatorId, otherId);
        Conversation convo = getOrCreateConversation(initiatorId, otherId);
        return toConversationView(convo, initiatorId);
    }

    private Conversation getOrCreateConversation(UUID a, UUID b) {
        UUID one = a.compareTo(b) <= 0 ? a : b;
        UUID two = a.compareTo(b) <= 0 ? b : a;

        return conversationRepository.findByParticipantOneAndParticipantTwo(one, two)
                .orElseGet(() -> {
                    // First contact → enforce the full gate before creating.
                    assertCanInitiate(a, b);
                    return conversationRepository.save(Conversation.builder()
                            .participantOne(one)
                            .participantTwo(two)
                            .lastMessageAt(LocalDateTime.now())
                            .createdAt(LocalDateTime.now())
                            .build());
                });
    }

    @Transactional(readOnly = true)
    public List<ConversationView> listConversations(UUID userId) {
        List<ConversationView> out = new ArrayList<>();
        for (Conversation c : conversationRepository.findAllForUser(userId)) {
            out.add(toConversationView(c, userId));
        }
        return out;
    }

    private ConversationView toConversationView(Conversation c, UUID viewerId) {
        UUID otherId = viewerId.equals(c.getParticipantOne())
                ? c.getParticipantTwo() : c.getParticipantOne();
        LocalDateTime lastRead = viewerId.equals(c.getParticipantOne())
                ? c.getLastReadAtOne() : c.getLastReadAtTwo();
        long unread = messageRepository.countByConversationIdAndSenderIdNotAndCreatedAtAfter(
                c.getId(), viewerId, lastRead != null ? lastRead : EPOCH);

        String username = null, photoUrl = null;
        User other = userRepository.findById(otherId).orElse(null);
        if (other != null) {
            username = other.getUsername();
            photoUrl = other.getPhotoUrl();
        }
        return new ConversationView(c.getId(), otherId, username, photoUrl,
                c.getLastMessagePreview(), c.getLastMessageAt(), unread);
    }

    // ═══════════════════════════════════════════════════════════
    // READ
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<MessageView> getMessages(UUID viewerId, UUID conversationId,
                                         LocalDateTime before, int limit) {
        Conversation convo = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found."));
        assertParticipant(convo, viewerId);

        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 100));
        List<Message> messages = (before == null)
                ? messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, page)
                : messageRepository.findByConversationIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                        conversationId, before, page);

        // Return ascending (oldest → newest) for natural rendering
        Collections.reverse(messages);
        List<MessageView> views = new ArrayList<>(messages.size());
        for (Message m : messages) views.add(MessageView.from(m));
        return views;
    }

    @Transactional
    public void markRead(UUID viewerId, UUID conversationId) {
        Conversation convo = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found."));
        assertParticipant(convo, viewerId);

        LocalDateTime now = LocalDateTime.now();
        if (viewerId.equals(convo.getParticipantOne())) {
            convo.setLastReadAtOne(now);
        } else {
            convo.setLastReadAtTwo(now);
        }
        conversationRepository.save(convo);
        messageRepository.markOtherPartyRead(conversationId, viewerId, now);
    }

    // ═══════════════════════════════════════════════════════════
    // BLOCK / REPORT / PRIVACY
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void blockUser(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) throw new RuntimeException("You can't block yourself.");
        if (!userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
            userBlockRepository.save(UserBlock.builder()
                    .blockerId(blockerId).blockedId(blockedId).build());
        }
    }

    @Transactional
    public void unblockUser(UUID blockerId, UUID blockedId) {
        userBlockRepository.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    /** Files a report into the existing moderation_actions pipeline. */
    @Transactional
    public void reportUser(UUID reporterId, UUID reportedUserId, String reason, String messageId) {
        User reporter = userRepository.findById(reporterId)
                .orElseThrow(() -> new RuntimeException("Reporter not found."));
        moderationActionRepository.save(ModerationAction.builder()
                .performedBy(reporter)
                .actionType("message_report")
                .targetType("user")
                .targetId(reportedUserId)
                .reason(reason)
                .details(messageId != null ? "messageId=" + messageId : null)
                .createdAt(LocalDateTime.now())
                .build());
        log.info("User {} reported user {} (reason: {})", reporterId, reportedUserId, reason);
    }

    @Transactional
    public void updateMessagePrivacy(UUID userId, boolean allowMessagesFromAnyone) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found."));
        user.setAllowMessagesFromAnyone(allowMessagesFromAnyone);
        userRepository.save(user);
    }

    // ═══════════════════════════════════════════════════════════
    // GATING
    // ═══════════════════════════════════════════════════════════

    /** Per-message guards: not self, sender phone-verified, neither side blocked. */
    private void assertCanSend(UUID senderId, UUID recipientId) {
        if (senderId.equals(recipientId)) {
            throw new RuntimeException("You can't message yourself.");
        }
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("User not found."));
        if (!Boolean.TRUE.equals(sender.getPhoneVerified())) {
            throw new RuntimeException("Verify your phone number to send messages.");
        }
        if (userBlockRepository.existsByBlockerIdAndBlockedId(recipientId, senderId)) {
            throw new RuntimeException("You can't message this person.");
        }
        if (userBlockRepository.existsByBlockerIdAndBlockedId(senderId, recipientId)) {
            throw new RuntimeException("Unblock this person to message them.");
        }
    }

    /**
     * First-contact gate. Recipient is reachable if their inbox is open
     * (default), OR they follow the sender, OR a conversation already exists.
     */
    private void assertCanInitiate(UUID senderId, UUID recipientId) {
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new RuntimeException("User not found."));

        // null or true => open (SoundCloud default). Only false => restricted.
        boolean open = !Boolean.FALSE.equals(recipient.getAllowMessagesFromAnyone());
        if (open) return;

        boolean recipientFollowsSender =
                followRepository.existsByFollower_UserIdAndFollowed_UserId(recipientId, senderId);
        if (recipientFollowsSender) return;

        throw new RuntimeException("This person only accepts messages from people they follow.");
    }

    private void assertParticipant(Conversation convo, UUID userId) {
        if (!userId.equals(convo.getParticipantOne()) && !userId.equals(convo.getParticipantTwo())) {
            throw new RuntimeException("You are not part of this conversation.");
        }
    }

    private String buildPreview(String body, UUID sharedSongId, Long supportPaymentId) {
        if (body != null && !body.isEmpty()) {
            return body.length() > PREVIEW_MAX ? body.substring(0, PREVIEW_MAX) : body;
        }
        if (supportPaymentId != null) return "Sent support";
        if (sharedSongId != null) return "Shared a track";
        return "";
    }
}