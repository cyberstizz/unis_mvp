package com.unis.controller;

import com.unis.service.ActivityTrackingService;
import com.unis.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {

    @Autowired
    private ActivityTrackingService activityTrackingService;

    /**
     * POST /api/v1/activity/track
     * Fire-and-forget activity tracking. Always returns 200.
     * Public endpoint — extracts userId from JWT if present, skips if not.
     */
    @PostMapping("/track")
    public ResponseEntity<Void> trackActivity(@RequestBody Map<String, String> request) {
        try {
            UUID userId = null;
            try {
                userId = SecurityUtils.getAuthenticatedUserId();
            } catch (Exception e) {
                // No valid JWT — skip tracking for anonymous users
                return ResponseEntity.ok().build();
            }

            String activityType = request.get("activityType");
            String page = request.get("page");

            if (activityType != null) {
                activityTrackingService.trackActivity(userId, activityType, page);
            }
        } catch (Exception e) {
            // Never fail — this is fire-and-forget
        }

        return ResponseEntity.ok().build();
    }
}