package com.unis.controller;

import com.unis.entity.Jurisdiction;
import com.unis.service.JurisdictionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    // GET /api/v1/jurisdictions/byName/{name}
    @GetMapping("/byName/{name}")
public ResponseEntity<List<Jurisdiction>> getByName(@PathVariable String name) {
    List<Jurisdiction> jurisdictions = jurisdictionService.getByName(name);
    return ResponseEntity.ok(jurisdictions);
}

    // GET /api/v1/jurisdictions/{id}/trending?type=...&genreId=...&limit=... (genreId optional)
    @GetMapping("/{jurisdictionId}/trending")
    public ResponseEntity<List<Object>> getTrendingMedia(
            @PathVariable UUID jurisdictionId, 
            @RequestParam String type, 
            @RequestParam(required = false) UUID genreId,  // Optional for FindPage
            @RequestParam(defaultValue = "30") int limit) {
        List<Object[]> trending = jurisdictionService.getTrendingMediaByJurisdiction(jurisdictionId, type, genreId, limit);
        return ResponseEntity.ok((List<Object>) (Object) trending);
    }

    /**
     * GET /api/v1/jurisdictions/{id}/children
     * Get direct children of a jurisdiction for map drill-down
     * Returns list of child jurisdictions with their polygons
     */
    @GetMapping("/{jurisdictionId}/children")
    public ResponseEntity<List<Jurisdiction>> getChildren(@PathVariable UUID jurisdictionId) {
        List<Jurisdiction> children = jurisdictionService.getChildren(jurisdictionId);
        return ResponseEntity.ok(children);
    }

    /**
     * GET /api/v1/jurisdictions/{id}/children/detailed
     * Get children with additional metadata (hasChildren, isActive flags)
     * More useful for the FindPage map UI
     */
    @GetMapping("/{jurisdictionId}/children/detailed")
    public ResponseEntity<List<Map<String, Object>>> getChildrenDetailed(@PathVariable UUID jurisdictionId) {
        List<Map<String, Object>> children = jurisdictionService.getChildrenWithMetadata(jurisdictionId);
        return ResponseEntity.ok(children);
    }

    /**
     * GET /api/v1/jurisdictions/roots
     * Get root jurisdictions (Unis and any others with no parent)
     */
    @GetMapping("/roots")
    public ResponseEntity<List<Jurisdiction>> getRoots() {
        List<Jurisdiction> roots = jurisdictionService.getRootJurisdictions();
        return ResponseEntity.ok(roots);
    }

    /**
     * GET /api/v1/jurisdictions/states
     * Get all states (children of Unis) - for the US map initial view
     */
    @GetMapping("/states")
    public ResponseEntity<List<Jurisdiction>> getStates() {
        List<Jurisdiction> states = jurisdictionService.getAllStates();
        return ResponseEntity.ok(states);
    }

    /**
     * GET /api/v1/jurisdictions/{id}/breadcrumb
     * Get the parent chain for a jurisdiction (for breadcrumb navigation)
     * Returns from root (Unis) down to the specified jurisdiction
     */
    @GetMapping("/{jurisdictionId}/breadcrumb")
    public ResponseEntity<List<Map<String, Object>>> getBreadcrumb(@PathVariable UUID jurisdictionId) {
        List<Map<String, Object>> chain = jurisdictionService.getParentChain(jurisdictionId);
        return ResponseEntity.ok(chain);
    }

    /**
     * GET /api/v1/jurisdictions/{id}/has-children
     * Check if a jurisdiction has children (for UI drill-down indicators)
     */
    @GetMapping("/{jurisdictionId}/has-children")
    public ResponseEntity<Map<String, Boolean>> hasChildren(@PathVariable UUID jurisdictionId) {
        boolean hasChildren = jurisdictionService.hasChildren(jurisdictionId);
        return ResponseEntity.ok(Map.of("hasChildren", hasChildren));
    }

    /**
     * GET /api/v1/jurisdictions/by-location?lat=...&lng=...
     * Find the most specific jurisdiction containing a geographic point
     * Used for user signup to auto-assign jurisdiction based on location
     */
    @GetMapping("/by-location")
    public ResponseEntity<Map<String, Object>> getByLocation(
            @RequestParam double lat,
            @RequestParam double lng) {
        Optional<Jurisdiction> jurisdiction = jurisdictionService.findJurisdictionByLocation(lat, lng);
        
        if (jurisdiction.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "found", false,
                "message", "No jurisdiction found for this location"
            ));
        }
        
        Jurisdiction jur = jurisdiction.get();
        List<Map<String, Object>> breadcrumb = jurisdictionService.getParentChain(jur.getJurisdictionId());
        
        return ResponseEntity.ok(Map.of(
            "found", true,
            "jurisdiction", jur,
            "breadcrumb", breadcrumb
        ));
    }
}