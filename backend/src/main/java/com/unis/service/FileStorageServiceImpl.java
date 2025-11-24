package com.unis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageServiceImpl implements FileStorageService {

    @Value("${file.storage.mode:local}")
    private String storageMode;

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    @Value("${cloudflare.r2.access-key:}")
    private String accessKey;

    @Value("${cloudflare.r2.secret-key:}")
    private String secretKey;

    @Value("${cloudflare.r2.endpoint:}")
    private String endpoint;

    @Value("${cloudflare.r2.bucket-name:}")
    private String bucketName;

    @Value("${cloudflare.r2.public-url:}")
    private String publicUrl;

    private S3Client s3Client;

    @PostConstruct
    public void initialize() {

        // Local mode → ensure uploads directory exists
        if (storageMode.equals("local")) {
            try {
                Files.createDirectories(Paths.get(uploadDir));
            } catch (IOException e) {
                throw new RuntimeException("Could not create local upload directory", e);
            }
        }

        // Production mode → initialize Cloudflare R2 client
        if (storageMode.equals("cloudflare")) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

            this.s3Client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .region(Region.of("auto"))
                    .build();
        }
    }

    @Override
    public String storeFile(MultipartFile file) {

        try {
            if (storageMode.equals("local")) {
                return storeFileLocally(file);
            } else {
                return storeFileInR2(file);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to store file: " + e.getMessage(), e);
        }
    }

    private String storeFileLocally(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";

        String uniqueFilename = UUID.randomUUID() + "-" + System.currentTimeMillis() + extension;

        Path targetLocation = Paths.get(uploadDir).resolve(uniqueFilename);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

        return "/uploads/" + uniqueFilename;
    }

    private String storeFileInR2(MultipartFile file) throws IOException {
        String fileName = UUID.randomUUID() + "-" + file.getOriginalFilename();

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));

        return publicUrl + "/" + fileName;
    }

    public void deleteFile(String fileUrl) {
        try {
            if (storageMode.equals("local")) {
                deleteLocalFile(fileUrl);
            } else {
                deleteR2File(fileUrl);
            }
        } catch (Exception ignored) {}
    }

    private void deleteLocalFile(String fileUrl) throws IOException {
        if (fileUrl.startsWith("/uploads/")) {
            Path filePath = Paths.get(uploadDir).resolve(fileUrl.replace("/uploads/", ""));
            Files.deleteIfExists(filePath);
        }
    }

    private void deleteR2File(String fileUrl) {
        if (fileUrl.startsWith(publicUrl)) {
            String key = fileUrl.replace(publicUrl + "/", "");
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
        }
    }
}
