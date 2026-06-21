package com.unis.service;

import com.unis.entity.ArtistPhoto;
import com.unis.repository.ArtistPhotoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Artist gallery photos. Stored in Cloudflare R2 via the existing
 * FileStorageService; one row per photo in artist_photos.
 *
 * MAX_PHOTOS is the single source of truth for the cap — change it here and the
 * frontend reads the same number. 15 keeps the gallery curated (a clean 3-wide
 * or 5-wide grid) without becoming a moderation/clutter burden; R2 storage is
 * not the constraint, page UX is.
 */
@Service
public class ArtistPhotoService {

    public static final int MAX_PHOTOS = 15;

    private final ArtistPhotoRepository repo;
    private final FileStorageService fileStorageService;

    public ArtistPhotoService(ArtistPhotoRepository repo, FileStorageService fileStorageService) {
        this.repo = repo;
        this.fileStorageService = fileStorageService;
    }

    public List<ArtistPhoto> list(UUID artistId) {
        return repo.findByArtistIdOrderByPositionAscCreatedAtAsc(artistId);
    }

    @Transactional
    public ArtistPhoto add(UUID artistId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No image provided.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed.");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("Each image must be 10MB or smaller.");
        }

        long count = repo.countByArtistId(artistId);
        if (count >= MAX_PHOTOS) {
            throw new IllegalStateException("You've reached the " + MAX_PHOTOS + "-photo limit. Remove one to add another.");
        }

        String url = fileStorageService.storeFile(file);

        ArtistPhoto photo = ArtistPhoto.builder()
                .artistId(artistId)
                .photoUrl(url)
                .position((int) count)
                .createdAt(LocalDateTime.now())
                .build();
        return repo.save(photo);
    }

    @Transactional
    public void delete(UUID artistId, UUID photoId) {
        ArtistPhoto photo = repo.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found."));
        if (!photo.getArtistId().equals(artistId)) {
            throw new SecurityException("You can only remove your own photos.");
        }
        try {
            fileStorageService.deleteFile(photo.getPhotoUrl());
        } catch (Exception ignored) {
            // best-effort R2 cleanup; never block the row delete on a storage hiccup
        }
        repo.delete(photo);
    }
}