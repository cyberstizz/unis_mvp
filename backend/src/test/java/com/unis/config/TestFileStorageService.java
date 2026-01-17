package com.unis.config;

import com.unis.service.FileStorageService;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Test stub for FileStorageService.
 * Does nothing - just satisfies Spring's dependency injection for tests.
 */
@Service
@Profile("test")
@Primary
public class TestFileStorageService implements FileStorageService {

    @Override
    public String storeFile(MultipartFile file) {
        // Return a fake URL for tests
        return "http://test.local/fake-file.mp3";
    }

    @Override
    public void deleteFile(String fileUrl) {
        // No-op for tests
    }
}