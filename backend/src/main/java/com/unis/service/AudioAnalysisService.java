package com.unis.service;

import com.unis.util.AudioDsp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Array;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AudioAnalysisService -- measures each song once, so the player can level
 * volume and mix songs together on the beat.
 *
 * What it measures (see AudioDsp for how):
 *  - loudness (LUFS), true peak and loudness range, with ffmpeg's ebur128
 *    filter (ITU-R BS.1770), on the original file;
 *  - tempo, beats, downbeats, key, where the music starts and stops, where the
 *    intro ends and the outro begins, and a waveform, from a mono 11 kHz copy.
 *
 * When:
 *  - right after an upload, in the background (the upload never waits for it);
 *  - songs that were already on Unis, and any upload that failed, by
 *    AudioAnalysisScheduler.
 *
 * The audio file is never changed. Results live in the songs table
 * (V2026_11g__playback_engine.sql). If ffmpeg is missing or a file can't be
 * read, the song just plays without levelling or beat-mixing, and the error is
 * kept in songs.analysis_error. After 3 failed tries the job stops retrying.
 */
@Service
public class AudioAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AudioAnalysisService.class);
    private static final int MAX_SECONDS = 900;               // decode at most 15 minutes
    private static final long MAX_DOWNLOAD = 300L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final String ffmpeg;
    private final long timeoutSec;
    private final ThreadPoolExecutor worker;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile Boolean ffmpegOk;

    public AudioAnalysisService(JdbcTemplate jdbc,
                                @Value("${unis.analysis.ffmpeg:ffmpeg}") String ffmpeg,
                                @Value("${unis.analysis.timeout-seconds:180}") long timeoutSec) {
        this.jdbc = jdbc;
        this.ffmpeg = ffmpeg;
        this.timeoutSec = timeoutSec;
        // One song at a time: measuring is CPU-heavy and never urgent.
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(40),
                r -> { Thread t = new Thread(r, "audio-analysis"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @PreDestroy
    void stop() { worker.shutdownNow(); }

    // ------------------------------------------------------------------ entry points

    /**
     * Called right after an upload is saved. Copies the uploaded file now
     * (Spring deletes it when the request ends) and measures it in the
     * background. Never throws: the upload has already succeeded.
     */
    public void analyzeUploadLater(UUID songId, MultipartFile file) {
        if (songId == null || file == null || file.isEmpty()) return;
        Path tmp;
        try {
            tmp = Files.createTempFile("unis-up-", suffixOf(file.getOriginalFilename()));
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("[Analysis] action=copy status=error song={} err={}", songId, e.getMessage());
            return;   // the catch-up job will measure it from storage
        }
        final Path path = tmp;
        try {
            worker.execute(() -> {
                try { analyzeAndSave(songId, path); }
                finally { deleteQuietly(path); }
            });
        } catch (RejectedExecutionException e) {
            deleteQuietly(path);
        }
    }

    /** Downloads a stored song and measures it (catch-up job). */
    public boolean analyzeStoredSong(UUID songId, String fileUrl) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("unis-dl-", suffixOf(fileUrl));
            HttpRequest req = HttpRequest.newBuilder(URI.create(fileUrl)).timeout(Duration.ofSeconds(90)).GET().build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() / 100 != 2) throw new IOException("download HTTP " + res.statusCode());
            try (InputStream in = res.body()) {
                long copied = copyLimited(in, tmp, MAX_DOWNLOAD);
                if (copied <= 0) throw new IOException("empty file");
            }
            return analyzeAndSave(songId, tmp) != null;
        } catch (Exception e) {
            recordFailure(songId, e.getMessage());
            return false;
        } finally {
            if (tmp != null) deleteQuietly(tmp);
        }
    }

    /** Measures a local file and saves the result. Returns the saved values, or null if it failed. */
    public Map<String, Object> analyzeAndSave(UUID songId, Path file) {
        long t0 = System.currentTimeMillis();
        try {
            if (!ffmpegAvailable()) throw new IllegalStateException("ffmpeg is not installed on the server");
            double[] loud = loudness(file);
            float[] pcm = decode(file);
            AudioDsp.Result r = AudioDsp.analyze(pcm);
            int updated = jdbc.update("""
                UPDATE songs SET
                    loudness_lufs = ?, true_peak_db = ?, loudness_range = ?,
                    bpm = ?, bpm_confidence = ?, beat_offset_ms = ?, downbeat_ms = ?,
                    music_key = ?, camelot = ?,
                    lead_silence_ms = ?, intro_end_ms = ?, outro_start_ms = ?, tail_silence_ms = ?,
                    waveform = ?, analyzed_at = NOW(), analysis_error = NULL,
                    duration = COALESCE(NULLIF(duration, 0), ?)
                WHERE song_id = ?
                """,
                loud[0], loud[1], loud[2],
                r.bpm, r.bpm != null ? r.bpmConfidence : null, r.beatOffsetMs, r.downbeatMs,
                r.key, r.camelot,
                r.leadSilenceMs, r.introEndMs, r.outroStartMs, r.tailSilenceMs,
                r.waveform, r.durationMs, songId);
            log.info("[Analysis] action=measure status=ok song={} lufs={} tp={} bpm={} key={} ms={}",
                    songId, loud[0], loud[1], r.bpm, r.camelot, System.currentTimeMillis() - t0);
            if (updated == 0) return null;
            List<Map<String, Object>> rows = find(List.of(songId), true);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[Analysis] action=measure status=error song={} err={}", songId, msg);
            recordFailure(songId, msg);
            return null;
        }
    }

    // ------------------------------------------------------------------ reads

    /**
     * The measured values for up to 100 songs, in the shape the web player
     * uses. Songs not measured yet come back with analyzed=false.
     */
    public List<Map<String, Object>> find(Collection<UUID> songIds, boolean withWaveform) {
        if (songIds == null || songIds.isEmpty()) return List.of();
        List<UUID> ids = songIds.stream().filter(Objects::nonNull).distinct().limit(100).toList();
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                SELECT song_id, duration, release_id, loudness_lufs, true_peak_db, loudness_range,
                       bpm, bpm_confidence, beat_offset_ms, downbeat_ms, music_key, camelot,
                       lead_silence_ms, intro_end_ms, outro_start_ms, tail_silence_ms,
                       %s, analyzed_at
                  FROM songs
                 WHERE song_id = ANY(?) AND deleted_at IS NULL
                """.formatted(withWaveform ? "waveform" : "NULL AS waveform"));
            Array arr = con.createArrayOf("uuid", ids.toArray());
            ps.setArray(1, arr);
            return ps;
        }, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("songId", rs.getObject("song_id"));
            m.put("analyzed", rs.getTimestamp("analyzed_at") != null);
            m.put("durationMs", rs.getObject("duration"));
            m.put("releaseId", rs.getObject("release_id"));
            m.put("lufs", num(rs.getBigDecimal("loudness_lufs")));
            m.put("truePeak", num(rs.getBigDecimal("true_peak_db")));
            m.put("lra", num(rs.getBigDecimal("loudness_range")));
            m.put("bpm", num(rs.getBigDecimal("bpm")));
            m.put("bpmConfidence", num(rs.getBigDecimal("bpm_confidence")));
            m.put("beatOffsetMs", rs.getObject("beat_offset_ms"));
            m.put("downbeatMs", rs.getObject("downbeat_ms"));
            m.put("key", rs.getString("music_key"));
            m.put("camelot", rs.getString("camelot"));
            m.put("leadSilenceMs", rs.getObject("lead_silence_ms"));
            m.put("introEndMs", rs.getObject("intro_end_ms"));
            m.put("outroStartMs", rs.getObject("outro_start_ms"));
            m.put("tailSilenceMs", rs.getObject("tail_silence_ms"));
            m.put("waveform", rs.getString("waveform"));
            return m;
        });
    }

    // ------------------------------------------------------------------ ffmpeg

    /** {integrated LUFS, true peak dBTP, loudness range LU}. */
    double[] loudness(Path file) throws Exception {
        List<String> cmd = List.of(ffmpeg, "-hide_banner", "-nostats", "-t", String.valueOf(MAX_SECONDS),
                "-i", file.toString(), "-map", "0:a:0", "-af", "ebur128=peak=true", "-f", "null", "-");
        String out = new String(run(cmd, true), StandardCharsets.UTF_8);
        int at = out.lastIndexOf("Summary:");
        if (at < 0) throw new IOException("loudness: no summary from ffmpeg");
        String sum = out.substring(at);
        Double lufs = grab(sum, "I:\\s+(-?[0-9.]+|-inf)\\s+LUFS");
        Double lra = grab(sum, "LRA:\\s+(-?[0-9.]+)\\s+LU");
        Double peak = grab(sum, "Peak:\\s+(-?[0-9.]+|-inf)\\s+dBFS");
        if (lufs == null || lufs < -70) throw new IOException("loudness: the song is silent");
        return new double[] { round2(lufs), peak == null ? 0 : round2(peak), lra == null ? 0 : round2(lra) };
    }

    /** Mono float samples at AudioDsp.SR. */
    float[] decode(Path file) throws Exception {
        List<String> cmd = List.of(ffmpeg, "-hide_banner", "-nostats", "-loglevel", "error",
                "-t", String.valueOf(MAX_SECONDS), "-i", file.toString(), "-map", "0:a:0",
                "-ac", "1", "-ar", String.valueOf(AudioDsp.SR), "-f", "f32le", "-");
        byte[] raw = run(cmd, false);
        if (raw.length < 4 * AudioDsp.SR) throw new IOException("decode: under a second of audio");
        float[] pcm = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm);
        return pcm;
    }

    /** Runs a command and returns stdout (or stdout+stderr when mergeErr). Kills it after the timeout. */
    private byte[] run(List<String> cmd, boolean mergeErr) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (mergeErr) pb.redirectErrorStream(true);
        else pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ffmpeg-watchdog"); t.setDaemon(true); return t; });
        watchdog.schedule(p::destroyForcibly, timeoutSec, TimeUnit.SECONDS);
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            in.transferTo(bos);
            int code = p.waitFor();
            if (code != 0) {
                String tail = mergeErr ? new String(bos.toByteArray(), StandardCharsets.UTF_8) : "";
                if (tail.length() > 200) tail = tail.substring(tail.length() - 200);
                throw new IOException("ffmpeg exit " + code + (tail.isBlank() ? "" : ": " + tail.trim()));
            }
            return bos.toByteArray();
        } finally {
            watchdog.shutdownNow();
            p.destroy();
        }
    }

    public boolean ffmpegAvailable() {
        Boolean ok = ffmpegOk;
        if (ok != null) return ok;
        try {
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-version").redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStreamSink.INSTANCE);
            ok = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) log.error("[Analysis] action=startup status=error err=ffmpeg not found fix=install ffmpeg (backend/Dockerfile)");
        ffmpegOk = ok;
        return ok;
    }

    // ------------------------------------------------------------------ helpers

    private void recordFailure(UUID songId, String msg) {
        try {
            jdbc.update("UPDATE songs SET analysis_attempts = analysis_attempts + 1, analysis_error = ? WHERE song_id = ?",
                    msg == null ? null : (msg.length() > 290 ? msg.substring(0, 290) : msg), songId);
        } catch (Exception e) {
            log.error("[Analysis] action=record status=error song={} err={} fix=run backend/sql/V2026_11g__playback_engine.sql",
                    songId, e.getMessage());
        }
    }

    private static Double grab(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        if (!m.find()) return null;
        String v = m.group(1);
        return v.equals("-inf") ? -120.0 : Double.parseDouble(v);
    }

    private static Double num(java.math.BigDecimal b) { return b == null ? null : b.doubleValue(); }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    private static String suffixOf(String name) {
        if (name == null) return ".audio";
        String n = name.toLowerCase(Locale.ROOT);
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot) : "";
        return ext.matches("\\.[a-z0-9]{2,5}") ? ext : ".audio";
    }

    private static long copyLimited(InputStream in, Path to, long max) throws IOException {
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (var out = Files.newOutputStream(to)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new IOException("file is larger than " + (max >> 20) + " MB");
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
    }

    /** Drains a stream into nothing. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    }
}
