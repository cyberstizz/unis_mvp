package com.unis.dto;

import java.time.LocalDateTime;

public class PreRegistrationResponse {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private String userType;
    private String stateCode;
    private String stateName;
    private String metroRegion;
    private String referralCode;
    private String referredBy;
    private Boolean converted;
    private LocalDateTime createdAt;

    // Region progress info (populated by service)
    private Long regionSignupCount;
    private Integer regionThreshold;
    private Double regionProgressPercent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }

    public String getStateName() { return stateName; }
    public void setStateName(String stateName) { this.stateName = stateName; }

    public String getMetroRegion() { return metroRegion; }
    public void setMetroRegion(String metroRegion) { this.metroRegion = metroRegion; }

    public String getReferralCode() { return referralCode; }
    public void setReferralCode(String referralCode) { this.referralCode = referralCode; }

    public String getReferredBy() { return referredBy; }
    public void setReferredBy(String referredBy) { this.referredBy = referredBy; }

    public Boolean getConverted() { return converted; }
    public void setConverted(Boolean converted) { this.converted = converted; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getRegionSignupCount() { return regionSignupCount; }
    public void setRegionSignupCount(Long regionSignupCount) { this.regionSignupCount = regionSignupCount; }

    public Integer getRegionThreshold() { return regionThreshold; }
    public void setRegionThreshold(Integer regionThreshold) { this.regionThreshold = regionThreshold; }

    public Double getRegionProgressPercent() { return regionProgressPercent; }
    public void setRegionProgressPercent(Double regionProgressPercent) { this.regionProgressPercent = regionProgressPercent; }
}