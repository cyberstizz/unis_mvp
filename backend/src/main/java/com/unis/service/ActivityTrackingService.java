package com.unis.service;

import com.unis.entity.User;
import com.unis.entity.UserActivity;
import com.unis.repository.UserActivityRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class ActivityTrackingService {

    @Autowired
    private UserActivityRepository userActivityRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * Track a user activity event.
     * Called fire-and-forget from the frontend — failures are silently ignored.
     */
    public void trackActivity(UUID userId, String activityType, String page) {
        if (userId == null) return; // Skip anonymous activity

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return; // Skip if user not found — don't throw

        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(activityType)
                .page(page)
                .build();

        userActivityRepository.save(activity);
    }
}