package com.unis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SearchDto {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Suggestion {
        private UUID id;
        private String name;
        private String subtitle;
        private String type;
        private String artworkUrl;
        private Integer score;
        private Float similarityScore;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Result {
        private UUID id;
        private String name;
        private String subtitle;
        private String type;
        private String artworkUrl;
        private Integer score;
        private Map<String, Object> extra;
        private Float similarityScore;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SuggestionsResponse {
        private Suggestion topResult;
        private List<Suggestion> artists;
        private List<Suggestion> songs;
        private List<Suggestion> jurisdictions;
        private int totalCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SearchResponse {
        private List<Result> results;
        private String query;
        private String type;
        private int count;
        private int offset;
        private int limit;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TrendingItem {
        private UUID id;
        private String name;
        private String subtitle;
        private String type;
        private String artworkUrl;
        private Integer score;
    }
}