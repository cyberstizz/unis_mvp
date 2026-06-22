package com.unis.controller;

import com.unis.dto.ConversationView;
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
    // GET /api/v1/conversations
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/conversations")
    public ResponseEntity<?> inbox() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<ConversationView> conversations = messagingService.listConversations(userId);
            return ResponseEntity.ok(conversations);
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
    // PRIVACY TOGGLE ("receive messages from anyone")
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