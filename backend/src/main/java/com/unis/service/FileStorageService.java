package com.unis.service;
//multipartfile
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
    String storeFile(MultipartFile file);
    void deleteFile(String fileUrl);

    // ── Hiding a removed song's files (MediaService.removeSong) ──────────────
    // Two steps so the database and storage can't disagree: the new, hidden
    // address is decided inside the transaction and saved with the song, and
    // the file is only moved there after the transaction commits.

    /**
     * The unguessable address a file will be hidden at. No I/O. Returns the
     * URL unchanged if this storage doesn't hide files.
     * Default (local development): files stay where they are.
     */
    default String hiddenLocationFor(String fileUrl) {
        return fileUrl;
    }

    /** Moves a file from one address to another. Default: nothing to move. */
    default void moveFile(String fromUrl, String toUrl) {
    }
}