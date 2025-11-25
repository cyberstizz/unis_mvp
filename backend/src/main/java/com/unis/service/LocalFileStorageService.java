package com.unis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
//just a comment as a test
@Service
@Profile("local")
public class LocalFileStorageService implements FileStorageService {

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    @Override
    public String storeFile(MultipartFile file) {
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String originalFilename = file.getOriginalFilename();
            String extension = (originalFilename != null && originalFilename.contains("."))
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : "";

            String fileName = UUID.randomUUID() + "-" + System.currentTimeMillis() + extension;
            Path targetLocation = uploadPath.resolve(fileName);

            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            return "/uploads/" + fileName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file locally", e);
        }
    }

    @Override
    public void deleteFile(String fileUrl) {
        if (fileUrl != null && fileUrl.startsWith("/uploads/")) {
            try {
                Path filePath = Paths.get(uploadDir).resolve(fileUrl.replace("/uploads/", ""));
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                // Log but don't fail (as in your original)
                System.err.println("Failed to delete local file: " + fileUrl + " - " + e.getMessage());
            }
        }
    }
}