package com.unis.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "pre_registrations")
public class PreRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "user_type", nullable = false, length = 20)
    private String userType = "LISTENER";

    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    @Column(name = "state_name", nullable = false, length = 100)
    private String stateName;

    @Column(name = "metro_region", nullable = false, length = 150)
    private String metroRegion;

    @Column(name = "city_freetext", length = 150)
    private String cityFreetext;

    @Column(name = "referral_code", nullable = false, unique = true, length = 20)
    private String referralCode;

    @Column(name = "referred_by", length = 20)
    private String referredBy;

    @Column(nullable = false)
    private Boolean converted = false;

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }

    public String getStateName() { return stateName; }
    public void setStateName(String stateName) { this.stateName = stateName; }

    public String getMetroRegion() { return metroRegion; }
    public void setMetroRegion(String metroRegion) { this.metroRegion = metroRegion; }

    public String getCityFreetext() { return cityFreetext; }
    public void setCityFreetext(String cityFreetext) { this.cityFreetext = cityFreetext; }

    public String getReferralCode() { return referralCode; }
    public void setReferralCode(String referralCode) { this.referralCode = referralCode; }

    public String getReferredBy() { return referredBy; }
    public void setReferredBy(String referredBy) { this.referredBy = referredBy; }

    public Boolean getConverted() { return converted; }
    public void setConverted(Boolean converted) { this.converted = converted; }

    public LocalDateTime getConvertedAt() { return convertedAt; }
    public void setConvertedAt(LocalDateTime convertedAt) { this.convertedAt = convertedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}