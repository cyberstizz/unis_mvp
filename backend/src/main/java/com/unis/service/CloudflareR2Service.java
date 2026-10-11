package com.unis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;

@Service
@Profile("prod")
public class CloudflareR2Service implements FileStorageService  {

    @Value("${cloudflare.r2.access-key}")
    private String accessKey;

    @Value("${cloudflare.r2.secret-key}")
    private String secretKey;

    @Value("${cloudflare.r2.endpoint}")
    private String endpoint;

    @Value("${cloudflare.r2.bucket-name}")
    private String bucketName;

    @Value("${cloudflare.r2.public-url}")
    private String publicUrl;

    private S3Client s3Client;

    @PostConstruct
    public void initializeS3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of("auto"))
                .build();
    }

    public String uploadFile(MultipartFile file, String folder) throws IOException {
        String fileName = folder + "/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(file.getBytes()));

        return publicUrl + "/" + fileName;
    }

    public void deleteFile(String fileUrl) {
        if (fileUrl != null && fileUrl.startsWith(publicUrl)) {
            String key = fileUrl.replace(publicUrl + "/", "");
            
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);

            // A hidden copy (removed/<random>/<old key>): also delete the old
            // key, in case moving the file there failed at removal time.
            // Deleting a key that doesn't exist is harmless.
            if (key.startsWith("removed/")) {
                int slash = key.indexOf('/', "removed/".length());
                if (slash > 0 && slash < key.length() - 1) {
                    s3Client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key.substring(slash + 1))
                            .build());
                }
            }
        }
    }

    /** Removed songs' files live under removed/<random>/<original key>. */
    private static final String REMOVED_PREFIX = "removed/";

    /**
     * Where a removed song's file will be hidden: removed/<random>/<old key>.
     * The random folder makes the URL unguessable, so the old URL (saved in
     * queues, playlists, shared links) leads nowhere once the file is moved.
     * RemovedMediaPurgeScheduler deletes it after 30 days; until then the song
     * can be restored by clearing its deleted_at.
     */
    @Override
    public String hiddenLocationFor(String fileUrl) {
        String prefix = publicUrl + "/";
        if (fileUrl == null || !fileUrl.startsWith(prefix)) {
            return fileUrl;
        }
        String key = fileUrl.substring(prefix.length());
        if (key.startsWith(REMOVED_PREFIX)) {
            return fileUrl; // already hidden
        }
        return prefix + REMOVED_PREFIX + UUID.randomUUID() + "/" + key;
    }

    /** Copies the object to its new key, then deletes the old one. */
    @Override
    public void moveFile(String fromUrl, String toUrl) {
        String prefix = publicUrl + "/";
        if (fromUrl == null || toUrl == null || fromUrl.equals(toUrl)
                || !fromUrl.startsWith(prefix) || !toUrl.startsWith(prefix)) {
            return;
        }
        String fromKey = fromUrl.substring(prefix.length());
        String toKey = toUrl.substring(prefix.length());

        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(fromKey)
                .destinationBucket(bucketName)
                .destinationKey(toKey)
                .build());

        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(fromKey)
                .build());
    }

    @Override
    public String storeFile(MultipartFile file) {
            System.out.println(" USING CLOUDFLARE R2 SERVICE");
        try {
            return uploadFile(file, "uploads"); 
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload to R2", e);
        }
    }
}