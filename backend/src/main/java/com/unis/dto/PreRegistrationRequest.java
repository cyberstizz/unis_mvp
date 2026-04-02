package com.unis.backend.dto;

// ─── Request DTO ───
public class PreRegistrationRequest {
    private String email;
    private String username;
    private String password;
    private String displayName;
    private String userType;       // "LISTENER" or "ARTIST"
    private String stateCode;      // "NY", "CA", etc.
    private String stateName;      // "New York", "California"
    private String metroRegion;    // "Greater Los Angeles" or "Other"
    private String cityFreetext;   // only if metroRegion == "Other"
    private String referredByCode; // optional referral code

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

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

    public String getReferredByCode() { return referredByCode; }
    public void setReferredByCode(String referredByCode) { this.referredByCode = referredByCode; }
}

