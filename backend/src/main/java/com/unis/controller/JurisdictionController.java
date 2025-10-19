package com.unis.controller;

import com.unis.entity.Jurisdiction;
import com.unis.service.JurisdictionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jurisdictions")
public class JurisdictionController {
    @Autowired
    private JurisdictionService jurisdictionService;

    // GET /api/v1/jurisdictions/{id} (page 8 details)
    @GetMapping("/{jurisdictionId}")
    public ResponseEntity<Jurisdiction> getJurisdiction(@PathVariable UUID jurisdictionId) {
        Jurisdiction jurisdiction = jurisdictionService.getJurisdiction(jurisdictionId);
        return ResponseEntity.ok(jurisdiction);
    }

    // GET /api/v1/jurisdictions/{id}/tops (page 8 top 30 artists/songs)
    @GetMapping("/{jurisdictionId}/tops")
    public ResponseEntity<Object> getJurisdictionTops(@PathVariable UUID jurisdictionId) {
        Object tops = jurisdictionService.getJurisdictionTops(jurisdictionId);
        return ResponseEntity.ok(tops);
    }

    // GET /api/v1/jurisdictions/{id}/trending?type=song (page 8 trending media)
    @GetMapping("/{jurisdictionId}/trending")
    public ResponseEntity<List<Object>> getTrendingMedia(@PathVariable UUID jurisdictionId, @RequestParam String type, @RequestParam(defaultValue = "30") int limit) {
        List<Object[]> trending = jurisdictionService.getTrendingMediaByJurisdiction(jurisdictionId, type, limit);
        return ResponseEntity.ok((List<Object>) (Object) trending);
    }
}