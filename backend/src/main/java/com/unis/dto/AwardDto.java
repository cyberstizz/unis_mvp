package com.unis.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AwardDto {
  private int rank;
  private String title;
  private String artist;
  private String jurisdiction;
  private Long votes;
  private String artwork;
  private String caption;
}