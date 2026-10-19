package com.unis.service;

import com.unis.dto.ConversationView;
import com.unis.dto.MessageSettingsView;
import com.unis.dto.MessageView;
import com.unis.entity.Conversation;
import com.unis.entity.ConversationStatus;
import com.unis.entity.Message;
import com.unis.entity.MessagePrivacy;
import com.unis.entity.MessageReaction;
import com.unis.entity.ModerationAction;
import com.unis.entity.User;
import com.unis.entity.UserBlock;
import com.unis.repository.ConversationRepository;
import com.unis.repository.FollowRepository;
import com.unis.repository.MessageReactionRepository;
import com.unis.repository.MessageRepository;
import com.unis.repository.ModerationActionRepository;
import com.unis.repository.SupportPaymentRepository;
import com.unis.repository.SupporterRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * All direct-messaging logic: the two lanes (inbox and Requests), the
 * first-contact rules, send (persist + real-time push), read receipts,
 * reactions, typing, message settings, and the block/report safety path.
 *
 * The rules are the ones the help center's "Messages" section states
 * (unis-sample src/data/helpContent.jsx). In short:
 *
 *   Where a new conversation lands, from the recipient's side:
 *     they follow the sender                      → inbox (ACCEPTED)
 *     the sender supports them (switch on)        → inbox (ACCEPTED)
 *     anyone else, privacy EVERYONE               → Requests (PENDING)
 *     anyone else, privacy PEOPLE_I_KNOW          → refused (supporters with the
 *                                                   switch off still reach Requests)
 *     anyone new, privacy NO_ONE                  → refused
 *     a block in either direction                 → refused, vaguely
 *   An existing conversation is never re-gated ("anyone you have already
 *   messaged can always reach you").
 *
 *   A request: one message, text only, counts toward the sender's
 *   MAX_NEW_REQUESTS_PER_DAY, no Seen mark ever, no sound/badge. Replying
 *   accepts it. Deleting it is silent. Bulk/low-quality ones go to Filtered.
 *
 * Transport split: messages are SENT over REST (reliable, transactional) and
 * RECEIVED in real time over STOMP:
 *   /user/queue/messages       new messages (both participants)
 *   /user/queue/read           Seen marks (mutual receipts only)
 *   /user/queue/reactions      reaction changes on a message
 *   /user/queue/typing         typing on / off
 *   /user/queue/conversations  a request was accepted (unlocks the sender's composer)
 *   /user/queue/presence       Active now (PresenceService)
 */
@Service
@Slf4j
public class MessagingService {

    /** Same number the help center quotes (helpContent.jsx MESSAGE_REQUEST_DAILY_LIMIT). */
    public static final int MAX_NEW_REQUESTS_PER_DAY = 15;

    /** One reaction per person per message, from this set: ❤️ 🔥 😂 😮 😢 👏 */
    public static final List<String> REACTIONS = List.of(
            "❤️", "🔥", "😂", "😮", "😢", "👏");

    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final int PREVIEW_MAX = 120;

    /** Spam filter: a link in a first message from a stranger. */
    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|io|co|ly|me|gg|xyz|info|biz|link|app|shop|site|ru|top)\\b)");
    /** Spam filter: the same text already sent this many times in the last day. */
    private static final int BULK_REPEAT_LIMIT = 2;

    private static final String TEXT_ONLY =
            "Tracks and support unlock once they accept your message. Send text for now.";
    private static final String BLOCKED = "You can't message this person.";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageReactionRepository messageReactionRepository;
    private final UserBlockRepository userBlockRepository;
    private final FollowRepository followRepository;
    private final SupporterRepository supporterRepository;
    private final SupportPaymentRepository supportPaymentRepository;
    private final UserRepository userRepository;
    private final ModerationActionRepository moderationActionRepository;
    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            MessageReactionRepository messageReactionRepository,
                            UserBlockRepository userBlockRepository,
                            FollowRepository followRepository,
                            SupporterRepository supporterRepository,
                            SupportPaymentRepository supportPaymentRepository,
                            UserRepository userRepository,
                            ModerationActionRepository moderationActionRepository,
                            PresenceService presenceService,
                            SimpMessagingTemplate messagingTemplate) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageReactionRepository = messageReactionRepository;
        this.userBlockRepository = userBlockRepository;
        this.followRepository = followRepository;
        this.supporterRepository = supporterRepository;
        this.supportPaymentRepository = supportPaymentRepository;
        this.userRepository = userRepository;
        this.moderationActionRepository = moderationActionRepository;
        this.presenceService = presenceService;
        this.messagingTemplate = messagingTemplate;
    }

    // ═══════════════════════════════════════════════════════════
    // SEND
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public MessageView sendMessage(UUID senderId, UUID recipientId, String body,
                                   UUID sharedSongId, Long supportPaymentId, String source) {

        String cleanBody = body == null ? null : body.trim();
        boolean hasText = cleanBody != null && !cleanBody.isEmpty();
        boolean hasAttachment = sharedSongId != null || supportPaymentId != null;
        if (!hasText && !hasAttachment) {
            throw new RuntimeException("Message can't be empty.");
        }

        assertCanSend(senderId, recipientId);
        LocalDateTime now = LocalDateTime.now();

        Conversation convo = findConversation(senderId, recipientId).orElse(null);
        if (convo == null) {
            // First contact — the lane rules decide where it lands.
            User recipient = requireUser(recipientId);
            ConversationStatus lane = resolveLane(senderId, recipient);
            boolean request = lane == ConversationStatus.PENDING;
            if (request) {
                if (hasAttachment) throw new RuntimeException(TEXT_ONLY);
                assertRequestQuota(senderId);
            }
            boolean filtered = request && looksLikeSpam(recipient, senderId, cleanBody, now);
            convo = createConversation(senderId, recipientId, lane, filtered, now);

        } else if (convo.getStatus() == ConversationStatus.PENDING) {
            if (senderId.equals(convo.getInitiatedBy())) {
                // The sender's own request: one message, text only.
                if (messageRepository.countByConversationIdAndSenderId(convo.getId(), senderId) > 0) {
                    throw new RuntimeException("You've already sent them a request. Wait for them to accept it.");
                }
                if (hasAttachment) throw new RuntimeException(TEXT_ONLY);
                // Opened earlier with no message (profile Message button) — the
                // first message is what the spam filter judges.
                convo.setFiltered(looksLikeSpam(requireUser(recipientId), senderId, cleanBody, now));
            } else {
                // Replying to a request accepts it.
                accept(convo, now);
                pushToUser(recipientId, "/queue/conversations",
                        Map.of("conversationId", convo.getId(), "status", ConversationStatus.ACCEPTED.name()));
            }
        }

        boolean request = convo.getStatus() == ConversationStatus.PENDING;

        Message message = messageRepository.save(Message.builder()
                .conversationId(convo.getId())
                .senderId(senderId)
                .body(cleanBody)
                .sharedSongId(sharedSongId)
                .supportPaymentId(supportPaymentId)
                .createdAt(now)
                .build());

        // Conversation metadata; the sender has implicitly read their own message.
        convo.setLastMessageAt(now);
        convo.setLastMessagePreview(buildPreview(cleanBody, sharedSongId, supportPaymentId));
        if (senderId.equals(convo.getParticipantOne())) {
            convo.setLastReadAtOne(now);
        } else {
            convo.setLastReadAtTwo(now);
        }
        conversationRepository.save(convo);

        MessageView view = MessageView.from(message, false, List.of(), request);
        if (convo.getDeclinedAt() == null) pushToUser(recipientId, "/queue/messages", view);
        pushToUser(senderId, "/queue/messages", view);

        log.info("Message {} sent in conversation {} ({} -> {}, {}{})",
                message.getId(), convo.getId(), senderId, recipientId,
                request ? "request" : "accepted", Boolean.TRUE.equals(convo.getFiltered()) ? ", filtered" : "");
        return view;
    }

    // ═══════════════════════════════════════════════════════════
    // CONVERSATIONS
    // ═══════════════════════════════════════════════════════════

    /** The profile "Message" button (mobile) — opens the thread, creating it if needed. */
    @Transactional
    public ConversationView startConversation(UUID initiatorId, UUID otherId) {
        assertCanSend(initiatorId, otherId);
        LocalDateTime now = LocalDateTime.now();
        Conversation convo = findConversation(initiatorId, otherId).orElse(null);
        if (convo == null) {
            ConversationStatus lane = resolveLane(initiatorId, requireUser(otherId));
            if (lane == ConversationStatus.PENDING) assertRequestQuota(initiatorId);
            convo = createConversation(initiatorId, otherId, lane, false, now);
        }
        User viewer = requireUser(initiatorId);
        return toConversationView(convo, viewer, laneFor(convo, initiatorId));
    }

    /**
     * Inbox rows for one lane: "chats", "requests", "filtered", or null/"all"
     * for every row the viewer can see (the mobile build's shape).
     */
    @Transactional(readOnly = true)
    public List<ConversationView> listConversations(UUID userId, String lane) {
        String wanted = (lane == null || lane.isBlank() || "all".equalsIgnoreCase(lane))
                ? null : lane.toLowerCase();
        User viewer = requireUser(userId);
        List<ConversationView> out = new ArrayList<>();
        for (Conversation c : conversationRepository.findAllForUser(userId)) {
            String rowLane = laneFor(c, userId);
            if (rowLane == null) continue;
            if (wanted != null && !wanted.equals(rowLane)) continue;
            out.add(toConversationView(c, viewer, rowLane));
        }
        return out;
    }

    /**
     * Counts for the sidebar badge and the Requests pill. Requests never add
     * to the badge — a request is not supposed to interrupt anyone.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(UUID userId) {
        long unread = 0;
        long unreadThreads = 0;
        long requests = 0;
        long filtered = 0;
        for (Conversation c : conversationRepository.findAllForUser(userId)) {
            String rowLane = laneFor(c, userId);
            if (rowLane == null) continue;
            switch (rowLane) {
                case "requests" -> requests++;
                case "filtered" -> filtered++;
                default -> {
                    long n = unreadFor(c, userId);
                    unread += n;
                    if (n > 0) unreadThreads++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unread", unread);
        out.put("unreadThreads", unreadThreads);
        out.put("requests", requests);
        out.put("filtered", filtered);
        return out;
    }

    /** Accept a request: it moves to the viewer's inbox and everything unlocks. */
    @Transactional
    public ConversationView acceptRequest(UUID viewerId, UUID conversationId) {
        Conversation convo = requireConversation(conversationId);
        assertRequestRecipient(convo, viewerId);
        accept(convo, LocalDateTime.now());
        conversationRepository.save(convo);
        pushToUser(convo.getInitiatedBy(), "/queue/conversations",
                Map.of("conversationId", convo.getId(), "status", ConversationStatus.ACCEPTED.name()));
        return toConversationView(convo, requireUser(viewerId), "chats");
    }

    /** Delete a request. Silent: nothing reaches the sender. */
    @Transactional
    public void declineRequest(UUID viewerId, UUID conversationId) {
        Conversation convo = requireConversation(conversationId);
        assertRequestRecipient(convo, viewerId);
        convo.setDeclinedAt(LocalDateTime.now());
        conversationRepository.save(convo);
        log.info("Request {} deleted by {}", conversationId, viewerId);
    }

    // ═══════════════════════════════════════════════════════════
    // READ
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<MessageView> getMessages(UUID viewerId, UUID conversationId,
                                         LocalDateTime before, int limit) {
        Conversation convo = requireConversation(conversationId);
        assertParticipant(convo, viewerId);
        if (laneFor(convo, viewerId) == null) throw new RuntimeException("Conversation not found.");

        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 100));
        List<Message> messages = (before == null)
                ? messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, page)
                : messageRepository.findByConversationIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                        conversationId, before, page);

        // Return ascending (oldest → newest) for natural rendering
        Collections.reverse(messages);

        // Seen marks only when both sides have receipts on and it's accepted.
        // (readAt is only ever stamped by a reader with receipts on.)
        boolean showReads = convo.getStatus() == ConversationStatus.ACCEPTED
                && receiptsOn(requireUser(viewerId));
        boolean request = convo.getStatus() == ConversationStatus.PENDING;

        Map<UUID, List<MessageView.Reaction>> reactions = reactionsFor(
                messages.stream().map(Message::getId).toList());

        List<MessageView> views = new ArrayList<>(messages.size());
        for (Message m : messages) {
            views.add(MessageView.from(m, showReads && m.getReadAt() != null,
                    reactions.getOrDefault(m.getId(), List.of()), request));
        }
        return views;
    }

    /**
     * Opening a thread always clears the viewer's own unread count. A Seen mark
     * goes back to the other person only in an accepted conversation, only when
     * the viewer has receipts on — and is only pushed if theirs are on too.
     */
    @Transactional
    public void markRead(UUID viewerId, UUID conversationId) {
        Conversation convo = requireConversation(conversationId);
        assertParticipant(convo, viewerId);

        LocalDateTime now = LocalDateTime.now();
        if (viewerId.equals(convo.getParticipantOne())) {
            convo.setLastReadAtOne(now);
        } else {
            convo.setLastReadAtTwo(now);
        }
        conversationRepository.save(convo);

        if (convo.getStatus() != ConversationStatus.ACCEPTED) return;   // a request never says Seen
        if (!receiptsOn(requireUser(viewerId))) return;

        int marked = messageRepository.markOtherPartyRead(conversationId, viewerId, now);
        UUID otherId = otherParticipant(convo, viewerId);
        if (marked > 0 && userRepository.findById(otherId).map(this::receiptsOn).orElse(false)) {
            pushToUser(otherId, "/queue/read", Map.of(
                    "conversationId", conversationId,
                    "readerId", viewerId,
                    "readAt", now.toString()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REACTIONS & TYPING
    // ═══════════════════════════════════════════════════════════

    /**
     * One reaction per person per message. Same emoji again removes it; a
     * different one replaces it. Accepted conversations only.
     */
    @Transactional
    public List<MessageView.Reaction> react(UUID viewerId, UUID messageId, String emoji) {
        if (emoji == null || !REACTIONS.contains(emoji)) {
            throw new RuntimeException("That reaction isn't available.");
        }
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found."));
        Conversation convo = requireConversation(message.getConversationId());
        assertParticipant(convo, viewerId);
        if (convo.getStatus() != ConversationStatus.ACCEPTED) {
            throw new RuntimeException("Reactions unlock once the request is accepted.");
        }
        UUID otherId = otherParticipant(convo, viewerId);
        if (blockedEitherWay(viewerId, otherId)) throw new RuntimeException(BLOCKED);

        Optional<MessageReaction> existing = messageReactionRepository.findByMessageIdAndUserId(messageId, viewerId);
        if (existing.isPresent()) {
            MessageReaction r = existing.get();
            if (emoji.equals(r.getEmoji())) {
                messageReactionRepository.delete(r);
            } else {
                r.setEmoji(emoji);
                r.setCreatedAt(LocalDateTime.now());
                messageReactionRepository.save(r);
            }
        } else {
            messageReactionRepository.save(MessageReaction.builder()
                    .messageId(messageId)
                    .userId(viewerId)
                    .emoji(emoji)
                    .build());
        }
        messageReactionRepository.flush();

        List<MessageView.Reaction> current = reactionsFor(List.of(messageId)).getOrDefault(messageId, List.of());
        Map<String, Object> payload = Map.of(
                "conversationId", convo.getId(),
                "messageId", messageId,
                "reactions", current);
        pushToUser(viewerId, "/queue/reactions", payload);
        pushToUser(otherId, "/queue/reactions", payload);
        return current;
    }

    /** Typing relay (MessagingSocketController). Accepted conversations only; silent on any problem. */
    @Transactional(readOnly = true)
    public void relayTyping(UUID senderId, UUID conversationId, boolean typing) {
        Conversation convo = conversationRepository.findById(conversationId).orElse(null);
        if (convo == null || convo.getStatus() != ConversationStatus.ACCEPTED) return;
        if (!senderId.equals(convo.getParticipantOne()) && !senderId.equals(convo.getParticipantTwo())) return;
        UUID otherId = otherParticipant(convo, senderId);
        if (blockedEitherWay(senderId, otherId)) return;
        pushToUser(otherId, "/queue/typing", Map.of(
                "conversationId", conversationId,
                "userId", senderId,
                "typing", typing));
    }

    // ═══════════════════════════════════════════════════════════
    // SETTINGS
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public MessageSettingsView getSettings(UUID userId) {
        User user = requireUser(userId);
        List<MessageSettingsView.Blocked> blocked = new ArrayList<>();
        for (UserBlock b : userBlockRepository.findByBlockerIdOrderByCreatedAtDesc(userId)) {
            User u = userRepository.findById(b.getBlockedId()).orElse(null);
            blocked.add(new MessageSettingsView.Blocked(
                    b.getBlockedId(),
                    u != null ? u.getUsername() : null,
                    u != null ? u.getPhotoUrl() : null,
                    b.getCreatedAt()));
        }
        return new MessageSettingsView(
                privacyOf(user).name(),
                !Boolean.FALSE.equals(user.getSupportersSkipRequests()),
                !Boolean.FALSE.equals(user.getShowActivityStatus()),
                receiptsOn(user),
                !Boolean.FALSE.equals(user.getFilterSpamRequests()),
                blocked);
    }

    /** Partial update — only the keys present in the body change. */
    @Transactional
    public MessageSettingsView updateSettings(UUID userId, Map<String, Object> body) {
        User user = requireUser(userId);
        boolean activityBefore = !Boolean.FALSE.equals(user.getShowActivityStatus());

        if (body.containsKey("messagePrivacy")) {
            MessagePrivacy privacy;
            try {
                privacy = MessagePrivacy.valueOf(String.valueOf(body.get("messagePrivacy")).toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Unknown message privacy setting.");
            }
            setPrivacy(user, privacy);
        }
        if (body.containsKey("supportersSkipRequests")) user.setSupportersSkipRequests(bool(body, "supportersSkipRequests"));
        if (body.containsKey("showActivityStatus")) user.setShowActivityStatus(bool(body, "showActivityStatus"));
        if (body.containsKey("sendReadReceipts")) user.setSendReadReceipts(bool(body, "sendReadReceipts"));
        if (body.containsKey("filterSpamRequests")) user.setFilterSpamRequests(bool(body, "filterSpamRequests"));
        userRepository.save(user);

        if (activityBefore != !Boolean.FALSE.equals(user.getShowActivityStatus())) {
            presenceService.republish(userId);
        }
        return getSettings(userId);
    }

    /** Legacy single switch (mobile build): true → Everyone, false → People you know. */
    @Transactional
    public void updateMessagePrivacy(UUID userId, boolean allowMessagesFromAnyone) {
        User user = requireUser(userId);
        setPrivacy(user, allowMessagesFromAnyone ? MessagePrivacy.EVERYONE : MessagePrivacy.PEOPLE_I_KNOW);
        userRepository.save(user);
    }

    private void setPrivacy(User user, MessagePrivacy privacy) {
        user.setMessagePrivacy(privacy);
        user.setAllowMessagesFromAnyone(privacy == MessagePrivacy.EVERYONE);   // keep the mobile flag in step
    }

    // ═══════════════════════════════════════════════════════════
    // BLOCK / REPORT
    // ═══════════════════════════════════════════════════════════

    /** Blocks both ways, and deletes any request the blocked person had waiting. */
    @Transactional
    public void blockUser(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) throw new RuntimeException("You can't block yourself.");
        if (!userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
            userBlockRepository.save(UserBlock.builder()
                    .blockerId(blockerId).blockedId(blockedId).build());
        }
        findConversation(blockerId, blockedId).ifPresent(c -> {
            if (c.getStatus() == ConversationStatus.PENDING
                    && blockedId.equals(c.getInitiatedBy())
                    && c.getDeclinedAt() == null) {
                c.setDeclinedAt(LocalDateTime.now());
                conversationRepository.save(c);
            }
        });
    }

    @Transactional
    public void unblockUser(UUID blockerId, UUID blockedId) {
        userBlockRepository.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    /** Files a report into the existing moderation_actions pipeline. */
    @Transactional
    public void reportUser(UUID reporterId, UUID reportedUserId, String reason, String messageId) {
        User reporter = requireUser(reporterId);
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

    // ═══════════════════════════════════════════════════════════
    // LANES
    // ═══════════════════════════════════════════════════════════

    /**
     * Where a brand-new conversation lands for the recipient, or a refusal.
     * Following is directional: it's who the RECIPIENT follows that counts.
     */
    private ConversationStatus resolveLane(UUID senderId, User recipient) {
        UUID recipientId = recipient.getUserId();

        if (followRepository.existsByFollower_UserIdAndFollowed_UserId(recipientId, senderId)) {
            return ConversationStatus.ACCEPTED;
        }
        boolean supporter = supports(senderId, recipientId);
        if (supporter && !Boolean.FALSE.equals(recipient.getSupportersSkipRequests())) {
            return ConversationStatus.ACCEPTED;
        }

        return switch (privacyOf(recipient)) {
            case EVERYONE -> ConversationStatus.PENDING;
            case PEOPLE_I_KNOW -> {
                // Supporters are "people you know"; with the skip switch off they wait in Requests.
                if (supporter) yield ConversationStatus.PENDING;
                throw new RuntimeException("This person only accepts messages from people they know.");
            }
            case NO_ONE -> throw new RuntimeException("This person isn't accepting new messages.");
        };
    }

    /**
     * Which lane a conversation sits in for this viewer, or null when it's
     * hidden from them (a request they deleted, or one opened with no message yet).
     */
    private String laneFor(Conversation c, UUID viewerId) {
        if (c.getStatus() != ConversationStatus.PENDING || viewerId.equals(c.getInitiatedBy())) {
            return "chats";
        }
        if (c.getDeclinedAt() != null) return null;
        if (c.getLastMessagePreview() == null) return null;
        return Boolean.TRUE.equals(c.getFiltered()) ? "filtered" : "requests";
    }

    private void accept(Conversation convo, LocalDateTime now) {
        convo.setStatus(ConversationStatus.ACCEPTED);
        convo.setAcceptedAt(now);
        convo.setDeclinedAt(null);
        convo.setFiltered(false);
    }

    /** Does {@code supporterId} support {@code artistId}: their supported artist, a supporter row, or a completed tip. */
    private boolean supports(UUID supporterId, UUID artistId) {
        User supporter = userRepository.findById(supporterId).orElse(null);
        if (supporter != null && artistId.equals(supporter.getSupportedArtistId())) return true;
        if (supporterRepository.supports(supporterId, artistId)) return true;
        return supportPaymentRepository.existsBySupporterIdAndArtistIdAndStatus(supporterId, artistId, "completed");
    }

    /** Bulk and low-quality first messages: a link, or the same text sent again and again. */
    private boolean looksLikeSpam(User recipient, UUID senderId, String body, LocalDateTime now) {
        if (Boolean.FALSE.equals(recipient.getFilterSpamRequests()) || body == null || body.isBlank()) return false;
        if (LINK.matcher(body).find()) return true;
        long sameText = messageRepository.countBySenderIdAndBodyAndCreatedAtAfter(senderId, body, now.minusDays(1));
        return sameText >= BULK_REPEAT_LIMIT;
    }

    private void assertRequestQuota(UUID senderId) {
        long started = conversationRepository.countRequestsStartedSince(senderId, LocalDateTime.now().minusDays(1));
        if (started >= MAX_NEW_REQUESTS_PER_DAY) {
            throw new RuntimeException(
                    "You've reached today's limit for messaging new people. Try again tomorrow.");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // VIEWS
    // ═══════════════════════════════════════════════════════════

    private ConversationView toConversationView(Conversation c, User viewer, String lane) {
        UUID viewerId = viewer.getUserId();
        UUID otherId = otherParticipant(c, viewerId);
        long unread = unreadFor(c, viewerId);

        String username = null, photoUrl = null, role = null, place = null;
        boolean otherSupportsMe = false, online = false;
        User other = userRepository.findById(otherId).orElse(null);
        if (other != null) {
            username = other.getUsername();
            photoUrl = other.getPhotoUrl();
            role = other.getRole() != null ? other.getRole().name() : null;
            place = other.getJurisdiction() != null ? other.getJurisdiction().getName() : null;
            otherSupportsMe = supports(otherId, viewerId);
            online = c.getStatus() == ConversationStatus.ACCEPTED && presenceService.isVisibleOnline(other, viewer);
        }

        Message last = messageRepository.findFirstByConversationIdOrderByCreatedAtDesc(c.getId());
        boolean lastMine = last != null && viewerId.equals(last.getSenderId());
        boolean lastSeen = lastMine && last.getReadAt() != null
                && c.getStatus() == ConversationStatus.ACCEPTED && receiptsOn(viewer);

        return new ConversationView(c.getId(), otherId, username, photoUrl,
                c.getLastMessagePreview(), c.getLastMessageAt(), unread,
                lane,
                viewerId.equals(c.getInitiatedBy()),
                c.getStatus() == ConversationStatus.PENDING,
                role, place, otherSupportsMe, online, lastMine, lastSeen);
    }

    private long unreadFor(Conversation c, UUID viewerId) {
        LocalDateTime lastRead = viewerId.equals(c.getParticipantOne())
                ? c.getLastReadAtOne() : c.getLastReadAtTwo();
        return messageRepository.countByConversationIdAndSenderIdNotAndCreatedAtAfter(
                c.getId(), viewerId, lastRead != null ? lastRead : EPOCH);
    }

    private Map<UUID, List<MessageView.Reaction>> reactionsFor(List<UUID> messageIds) {
        Map<UUID, List<MessageView.Reaction>> out = new HashMap<>();
        if (messageIds.isEmpty()) return out;
        for (MessageReaction r : messageReactionRepository.findByMessageIdInOrderByCreatedAtAsc(messageIds)) {
            out.computeIfAbsent(r.getMessageId(), k -> new ArrayList<>())
                    .add(new MessageView.Reaction(r.getUserId(), r.getEmoji()));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════
    // GUARDS & HELPERS
    // ═══════════════════════════════════════════════════════════

    /** Per-message guards: not self, recipient exists, sender phone-verified, no block either way. */
    private void assertCanSend(UUID senderId, UUID recipientId) {
        if (senderId.equals(recipientId)) {
            throw new RuntimeException("You can't message yourself.");
        }
        // Deleted accounts keep their past threads (the other person's history)
        // but can't receive anything new.
        User recipientAccount = userRepository.findById(recipientId).orElse(null);
        if (recipientAccount == null || recipientAccount.getDeletedAt() != null) {
            throw new RuntimeException("This account no longer exists.");
        }
        User sender = requireUser(senderId);
        if (!Boolean.TRUE.equals(sender.getPhoneVerified())) {
            throw new RuntimeException("Verify your phone number to send messages.");
        }
        // Deliberately the same words both ways (help center: a block is vague).
        if (blockedEitherWay(senderId, recipientId)) {
            throw new RuntimeException(BLOCKED);
        }
    }

    private void assertRequestRecipient(Conversation convo, UUID viewerId) {
        assertParticipant(convo, viewerId);
        if (convo.getStatus() != ConversationStatus.PENDING
                || viewerId.equals(convo.getInitiatedBy())
                || convo.getDeclinedAt() != null) {
            throw new RuntimeException("This isn't a message request.");
        }
    }

    private void assertParticipant(Conversation convo, UUID userId) {
        if (!userId.equals(convo.getParticipantOne()) && !userId.equals(convo.getParticipantTwo())) {
            throw new RuntimeException("You are not part of this conversation.");
        }
    }

    private boolean blockedEitherWay(UUID a, UUID b) {
        return userBlockRepository.existsByBlockerIdAndBlockedId(a, b)
                || userBlockRepository.existsByBlockerIdAndBlockedId(b, a);
    }

    private Optional<Conversation> findConversation(UUID a, UUID b) {
        UUID one = a.compareTo(b) <= 0 ? a : b;
        UUID two = a.compareTo(b) <= 0 ? b : a;
        return conversationRepository.findByParticipantOneAndParticipantTwo(one, two);
    }

    private Conversation createConversation(UUID initiatorId, UUID recipientId, ConversationStatus status,
                                            boolean filtered, LocalDateTime now) {
        UUID one = initiatorId.compareTo(recipientId) <= 0 ? initiatorId : recipientId;
        UUID two = initiatorId.compareTo(recipientId) <= 0 ? recipientId : initiatorId;
        boolean request = status == ConversationStatus.PENDING;
        return conversationRepository.save(Conversation.builder()
                .participantOne(one)
                .participantTwo(two)
                .initiatedBy(initiatorId)
                .status(status)
                .startedAsRequest(request)
                .acceptedAt(request ? null : now)
                .filtered(filtered)
                .lastMessageAt(now)
                .createdAt(now)
                .build());
    }

    private Conversation requireConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found."));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found."));
    }

    private static UUID otherParticipant(Conversation c, UUID viewerId) {
        return viewerId.equals(c.getParticipantOne()) ? c.getParticipantTwo() : c.getParticipantOne();
    }

    private static MessagePrivacy privacyOf(User u) {
        return u.getMessagePrivacy() != null ? u.getMessagePrivacy()
                : MessagePrivacy.fromLegacy(u.getAllowMessagesFromAnyone());
    }

    private boolean receiptsOn(User u) {
        return !Boolean.FALSE.equals(u.getSendReadReceipts());
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private void pushToUser(UUID userId, String destination, Object payload) {
        if (userId == null) return;
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), destination, payload);
        } catch (Exception e) {
            // Never let a transient WS failure roll back a persisted change.
            log.warn("STOMP push {} to {} failed: {}", destination, userId, e.getMessage());
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
