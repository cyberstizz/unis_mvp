package com.unis.controller;

import com.unis.dto.ConversationView;
import com.unis.dto.MessageSettingsView;
import com.unis.dto.MessageView;
import com.unis.service.MessagingService;
import com.unis.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class MessageController {

    private final MessagingService messagingService;

    public MessageController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    // ─────────────────────────────────────────────────────────────
    // SEND
    // POST /api/v1/messages
    // Body: { recipientId, body?, sharedSongId?, supportPaymentId?, source? }
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/messages")
    public ResponseEntity<?> send(@RequestBody Map<String, Object> body) {
        try {
            UUID senderId = SecurityUtils.getAuthenticatedUserId();

            if (body.get("recipientId") == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "recipientId is required"));
            }
            UUID recipientId = UUID.fromString(body.get("recipientId").toString());
            String text = body.get("body") != null ? body.get("body").toString() : null;
            UUID sharedSongId = body.get("sharedSongId") != null
                    ? UUID.fromString(body.get("sharedSongId").toString()) : null;
            Long supportPaymentId = body.get("supportPaymentId") != null
                    ? Long.parseLong(body.get("supportPaymentId").toString()) : null;
            String source = body.get("source") != null ? body.get("source").toString() : "dm";

            MessageView view = messagingService.sendMessage(
                    senderId, recipientId, text, sharedSongId, supportPaymentId, source);
            return ResponseEntity.ok(view);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid id format"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // START / OPEN a conversation (the profile "Message" button)
    // POST /api/v1/messages/start  Body: { recipientId }
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/messages/start")
    public ResponseEntity<?> start(@RequestBody Map<String, String> body) {
        try {
            UUID initiatorId = SecurityUtils.getAuthenticatedUserId();
            if (body.get("recipientId") == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "recipientId is required"));
            }
            UUID recipientId = UUID.fromString(body.get("recipientId"));
            ConversationView view = messagingService.startConversation(initiatorId, recipientId);
            return ResponseEntity.ok(view);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // INBOX
    // GET /api/v1/conversations?lane=chats|requests|filtered
    // No lane = every row you can see (the mobile build's shape).
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/conversations")
    public ResponseEntity<?> inbox(@RequestParam(required = false) String lane) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<ConversationView> conversations = messagingService.listConversations(userId, lane);
            return ResponseEntity.ok(conversations);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // COUNTS (sidebar badge + Requests pill)
    // GET /api/v1/conversations/summary
    //   → { unread, unreadThreads, requests, filtered }
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/conversations/summary")
    public ResponseEntity<?> summary() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            return ResponseEntity.ok(messagingService.summary(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // REQUESTS
    // POST /api/v1/conversations/{conversationId}/accept   → ConversationView
    // POST /api/v1/conversations/{conversationId}/decline  (silent delete)
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/conversations/{conversationId}/accept")
    public ResponseEntity<?> accept(@PathVariable UUID conversationId) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            return ResponseEntity.ok(messagingService.acceptRequest(userId, conversationId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/conversations/{conversationId}/decline")
    public ResponseEntity<?> decline(@PathVariable UUID conversationId) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            messagingService.declineRequest(userId, conversationId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // THREAD
    // GET /api/v1/conversations/{conversationId}/messages?before=&limit=
    // (Frontend should send this with useCache:false — GET responses are cached.)
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<?> messages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(defaultValue = "40") int limit) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<MessageView> messages =
                    messagingService.getMessages(userId, conversationId, before, limit);
            return ResponseEntity.ok(messages);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // MARK READ
    // POST /api/v1/conversations/{conversationId}/read
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<?> markRead(@PathVariable UUID conversationId) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            messagingService.markRead(userId, conversationId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // BLOCK / UNBLOCK
    // POST   /api/v1/users/{userId}/block
    // DELETE /api/v1/users/{userId}/block
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/users/{userId}/block")
    public ResponseEntity<?> block(@PathVariable UUID userId) {
        try {
            UUID me = SecurityUtils.getAuthenticatedUserId();
            messagingService.blockUser(me, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{userId}/block")
    public ResponseEntity<?> unblock(@PathVariable UUID userId) {
        try {
            UUID me = SecurityUtils.getAuthenticatedUserId();
            messagingService.unblockUser(me, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // REPORT
    // POST /api/v1/messages/report  Body: { reportedUserId, reason, messageId? }
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/messages/report")
    public ResponseEntity<?> report(@RequestBody Map<String, String> body) {
        try {
            UUID me = SecurityUtils.getAuthenticatedUserId();
            if (body.get("reportedUserId") == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "reportedUserId is required"));
            }
            UUID reportedUserId = UUID.fromString(body.get("reportedUserId"));
            messagingService.reportUser(me, reportedUserId, body.get("reason"), body.get("messageId"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // REACT (one per person per message; same emoji again removes it)
    // POST /api/v1/messages/{messageId}/reaction  Body: { emoji }
    //   → [ { userId, emoji } ]
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/messages/{messageId}/reaction")
    public ResponseEntity<?> react(@PathVariable UUID messageId, @RequestBody Map<String, String> body) {
        try {
            UUID me = SecurityUtils.getAuthenticatedUserId();
            List<MessageView.Reaction> reactions = messagingService.react(me, messageId, body.get("emoji"));
            return ResponseEntity.ok(reactions);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // MESSAGE SETTINGS
    // GET /api/v1/messages/settings
    // PUT /api/v1/messages/settings  Body (any subset): { messagePrivacy,
    //     supportersSkipRequests, showActivityStatus, sendReadReceipts, filterSpamRequests }
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/messages/settings")
    public ResponseEntity<?> settings() {
        try {
            UUID me = SecurityUtils.getAuthenticatedUserId();
            MessageSettingsView view = messagingService.getSettings(me);
            return ResponseEntity.ok(view);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/messages/settings")
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Object> body) {
        try {
            UUID me = SecurityUtils.getAuthenticatedUserId();
            MessageSettingsView view = messagingService.updateSettings(me, body);
            return ResponseEntity.ok(view);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LEGACY PRIVACY TOGGLE ("receive messages from anyone") — mobile build.
    // true → Everyone, false → People you know. The web uses /messages/settings.
    // PUT /api/v1/messages/privacy  Body: { allowMessagesFromAnyone: true|false }
    // ─────────────────────────────────────────────────────────────
    @PutMapping("/messages/privacy")
    public ResponseEntity<?> privacy(@RequestBody Map<String, Object> body) {
        try {
            UUID me = SecurityUtils.getAuthenticatedUserId();
            boolean allow = body.get("allowMessagesFromAnyone") == null
                    || Boolean.parseBoolean(body.get("allowMessagesFromAnyone").toString());
            messagingService.updateMessagePrivacy(me, allow);
            return ResponseEntity.ok(Map.of("success", true, "allowMessagesFromAnyone", allow));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}