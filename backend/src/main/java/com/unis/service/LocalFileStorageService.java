package com.unis.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path uploadDir;

    public LocalFileStorageService() throws IOException {
        this.uploadDir = Paths.get("src/main/resources/static/uploads").toAbsolutePath().normalize();
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
    }

    @Override
    public String storeFile(MultipartFile file) {
        try {
            String original = StringUtils.cleanPath(file.getOriginalFilename());
            String ext = "";

            int idx = original.lastIndexOf('.');
            if (idx > -1) ext = original.substring(idx);

            String filename = UUID.randomUUID().toString() + "-" + Instant.now().toEpochMilli() + ext;
            Path target = this.uploadDir.resolve(filename);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            return "/uploads/" + filename;  // Relative URL for serving
        } catch (IOException ex) {
            throw new RuntimeException("Failed to store file", ex);
        }
    }
}