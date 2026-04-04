package com.unis.controller;

import com.unis.dto.SearchDto;
import com.unis.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;

    @Autowired
    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Lightweight autocomplete suggestions.
     * Called on every keystroke (debounced 250ms on frontend).
     * Target: < 150ms response time.
     *
     * GET /api/v1/search/suggestions?q=har&limit=10
     */
    @GetMapping("/suggestions")
    public ResponseEntity<SearchDto.SuggestionsResponse> getSuggestions(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {

        SearchDto.SuggestionsResponse response = searchService.getSuggestions(query, limit);
        return ResponseEntity.ok(response);
    }

    /**
     * Full search with type filtering, jurisdiction scoping, and pagination.
     * Called when user presses Enter or taps "See all results".
     *
     * GET /api/v1/search?q=harlem&type=all&jurisdictionId=...&limit=20&offset=0
     */
    @GetMapping
    public ResponseEntity<SearchDto.SearchResponse> search(
            @RequestParam("q") String query,
            @RequestParam(value = "type", defaultValue = "all") String type,
            @RequestParam(value = "jurisdictionId", required = false) UUID jurisdictionId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {

        SearchDto.SearchResponse response = searchService.search(query, type, jurisdictionId, limit, offset);
        return ResponseEntity.ok(response);
    }

    /**
     * Trending items for zero-state search (before user types anything).
     * Returns top songs by plays_today for a given jurisdiction.
     *
     * GET /api/v1/search/trending?jurisdictionId=...&limit=5
     */
    @GetMapping("/trending")
    public ResponseEntity<List<SearchDto.TrendingItem>> getTrending(
            @RequestParam(value = "jurisdictionId", required = false) UUID jurisdictionId,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {

        List<SearchDto.TrendingItem> trending = searchService.getTrending(jurisdictionId, limit);
        return ResponseEntity.ok(trending);
    }
}