package com.unis.controller;

import com.unis.entity.DmcaClaim;
import com.unis.service.DmcaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/dmca")
public class DmcaPublicController {

    @Autowired
    private DmcaService dmcaService;

    /**
     * POST /api/v1/dmca/submit
     * Public endpoint — receives DMCA notices from ReportInfringement.jsx
     */
    @PostMapping("/submit")
    public ResponseEntity<?> submitClaim(@RequestBody Map<String, String> request) {
        try {
            String claimantName = request.get("claimantName");
            String claimantEmail = request.get("claimantEmail");
            String claimantPhone = request.get("claimantPhone");
            String claimantCompany = request.get("claimantCompany");
            String copyrightOwner = request.get("copyrightOwner");
            String workDescription = request.get("workDescription");
            String originalWorkUrl = request.get("originalWorkUrl");
            String infringingUrl = request.get("infringingUrl");
            String signature = request.get("signature");

            // Validate required fields
            if (claimantName == null || claimantName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Claimant name is required"));
            }
            if (claimantEmail == null || claimantEmail.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Claimant email is required"));
            }
            if (copyrightOwner == null || copyrightOwner.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Copyright owner is required"));
            }
            if (workDescription == null || workDescription.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Work description is required"));
            }
            if (infringingUrl == null || infringingUrl.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Infringing URL is required"));
            }
            if (signature == null || signature.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Signature is required"));
            }

            DmcaClaim claim = dmcaService.submitClaim(
                    claimantName, claimantEmail, claimantPhone, claimantCompany,
                    copyrightOwner, workDescription, originalWorkUrl, infringingUrl, signature);

            String referenceNumber = "DMCA-" + claim.getClaimId().toString().substring(0, 8).toUpperCase();

            return ResponseEntity.ok(Map.of(
                    "message", "DMCA notice submitted successfully",
                    "referenceNumber", referenceNumber
            ));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to submit DMCA notice: " + e.getMessage()));
        }
    }
}