// src/main/java/com/unis/dto/RegisterResponse.java
package com.unis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterResponse {
    private UUID userId;
    private String role;
    private String signupToken;          
    private boolean emailVerificationSent;
}