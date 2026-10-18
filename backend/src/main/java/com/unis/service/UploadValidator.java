package com.unis.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decides what an uploaded file really is from its first bytes, and refuses
 * anything that isn't an image, audio or video file.
 *
 * Why: uploads are served publicly from cdn.unismusic.com with the content
 * type they're stored under. Before this, that type came straight from the
 * uploader, so a web page or script uploaded as a "profile photo" (the signup
 * photo upload needs no account) would be served from a Unis address as a
 * live page — a ready-made phishing or malware host with Unis's name on it.
 * The file's own name and claimed type are now ignored: it is stored under a
 * random name with the extension and content type that match its contents.
 *
 * SVG is deliberately not accepted: it is an image format that can carry
 * scripts.
 */
public final class UploadValidator {

    public enum Kind { IMAGE, AUDIO, VIDEO }

    /** What a file was recognised as: its broad kind, real content type and file extension. */
    public record Detected(Kind kind, String contentType, String extension) {}

    private UploadValidator() {}

    public static final String SUPPORTED =
        "Images: JPG, PNG, WEBP, GIF, HEIC, AVIF. Audio: MP3, WAV, M4A, AAC, FLAC, OGG, AIFF. Video: MP4, MOV, WEBM.";

    /** Recognise the file or refuse it with HTTP 400. */
    public static Detected detect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty.");
        }
        byte[] head = new byte[64];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file couldn't be read.");
        }
        Detected d = sniff(head, n);
        if (d == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "That file type isn't supported. " + SUPPORTED);
        }
        return d;
    }

    /** Recognise the file, and also require a particular kind (e.g. a photo must be an image). */
    public static Detected require(MultipartFile file, Kind expected, long maxBytes) {
        Detected d = detect(file);
        if (d.kind() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Please choose " + (expected == Kind.IMAGE ? "an image" : expected == Kind.AUDIO ? "an audio file" : "a video") + ".");
        }
        if (maxBytes > 0 && file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "That file is too large. The limit is " + (maxBytes / (1024 * 1024)) + " MB.");
        }
        return d;
    }

    static Detected sniff(byte[] b, int n) {
        // ── Images ──
        if (n >= 3 && u(b[0]) == 0xFF && u(b[1]) == 0xD8 && u(b[2]) == 0xFF)
            return new Detected(Kind.IMAGE, "image/jpeg", "jpg");
        if (n >= 8 && u(b[0]) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
            return new Detected(Kind.IMAGE, "image/png", "png");
        if (n >= 6 && ascii(b, 0, 4).equals("GIF8"))
            return new Detected(Kind.IMAGE, "image/gif", "gif");
        if (n >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP"))
            return new Detected(Kind.IMAGE, "image/webp", "webp");

        // ── Audio ──
        if (n >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WAVE"))
            return new Detected(Kind.AUDIO, "audio/wav", "wav");
        if (n >= 12 && ascii(b, 0, 4).equals("FORM") && (ascii(b, 8, 4).equals("AIFF") || ascii(b, 8, 4).equals("AIFC")))
            return new Detected(Kind.AUDIO, "audio/aiff", "aiff");
        if (n >= 4 && ascii(b, 0, 4).equals("fLaC"))
            return new Detected(Kind.AUDIO, "audio/flac", "flac");
        if (n >= 4 && ascii(b, 0, 4).equals("OggS"))
            return new Detected(Kind.AUDIO, "audio/ogg", "ogg");
        if (n >= 3 && ascii(b, 0, 3).equals("ID3"))
            return new Detected(Kind.AUDIO, "audio/mpeg", "mp3");
        if (n >= 2 && u(b[0]) == 0xFF && (u(b[1]) & 0xF6) == 0xF0)          // AAC (ADTS)
            return new Detected(Kind.AUDIO, "audio/aac", "aac");
        if (n >= 2 && u(b[0]) == 0xFF && (u(b[1]) & 0xE0) == 0xE0)          // MP3 frame without a tag
            return new Detected(Kind.AUDIO, "audio/mpeg", "mp3");

        // ── ISO base media (MP4, M4A, MOV, HEIC, AVIF): "ftyp" at byte 4, brand at byte 8 ──
        if (n >= 12 && ascii(b, 4, 4).equals("ftyp")) {
            String brand = ascii(b, 8, 4);
            switch (brand) {
                case "heic", "heix", "hevc", "hevx", "mif1", "msf1":
                    return new Detected(Kind.IMAGE, "image/heic", "heic");
                case "avif", "avis":
                    return new Detected(Kind.IMAGE, "image/avif", "avif");
                case "M4A ", "M4B ", "M4P ":
                    return new Detected(Kind.AUDIO, "audio/mp4", "m4a");
                case "qt  ":
                    return new Detected(Kind.VIDEO, "video/quicktime", "mov");
                default:
                    return new Detected(Kind.VIDEO, "video/mp4", "mp4");
            }
        }

        // ── WebM / Matroska ──
        if (n >= 4 && u(b[0]) == 0x1A && u(b[1]) == 0x45 && u(b[2]) == 0xDF && u(b[3]) == 0xA3)
            return new Detected(Kind.VIDEO, "video/webm", "webm");

        return null;
    }

    private static int u(byte x) {
        return x & 0xFF;
    }

    private static String ascii(byte[] b, int from, int len) {
        return new String(b, from, len, StandardCharsets.ISO_8859_1);
    }
}
