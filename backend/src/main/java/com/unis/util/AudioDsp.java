package com.unis.util;

import java.util.Arrays;
import java.util.Base64;

/**
 * AudioDsp -- everything Unis measures from a song's samples, in plain Java.
 *
 * Input: mono float PCM at {@link #SR} Hz (ffmpeg decodes and downmixes).
 * Output ({@link Result}): tempo, where the beats and downbeats fall, the key
 * (and its Camelot code), where the music starts and stops, where the intro
 * ends and the outro begins, and a 1000-point waveform.
 *
 * Loudness and true peak are NOT measured here: ffmpeg's ebur128 filter does
 * that to the ITU-R BS.1770 standard, on the full-quality file.
 *
 * How each part works:
 *  - Spectrum: 2048-point FFT, Hann window, hop 256 (about 43 frames a second).
 *  - Onsets: spectral flux on log-compressed magnitudes (new energy arriving),
 *    with the local average removed, so sustained notes don't count.
 *  - Tempo: autocorrelation of the onset curve at fractional lags, with the
 *    double and quadruple lag added in (beats repeat), weighted toward
 *    80-160 BPM so a song isn't read at half or double speed. Then refined to
 *    0.02 BPM by lining a beat grid up against the whole song, and snapped to
 *    a whole number when that fits as well (most songs are made on a grid).
 *  - Beats: the grid phase that lands on the most onsets.
 *  - Downbeats: of the four beats in a bar, the one where the kick hits
 *    hardest and the harmony changes most, summed over the song.
 *  - Key: a 12-note chroma summed over the song, matched against the
 *    Krumhansl-Kessler major and minor key profiles.
 *  - Intro/outro: loudness per bar against the song's typical bar loudness.
 *    The intro ends at the first two bars in a row at full level; the outro
 *    starts after the last two bars in a row at full level.
 */
public final class AudioDsp {

    public static final int SR = 11025;
    static final int N = 2048;
    static final int HOP = 256;
    static final double FPS = (double) SR / HOP;
    static final double SILENCE_DB = -50.0;

    private AudioDsp() {}

    public static final class Result {
        public int durationMs;
        public Double bpm;            // null when there is no steady beat
        public double bpmConfidence;  // 0..1
        public Integer beatOffsetMs;
        public Integer downbeatMs;
        public String key;
        public String camelot;
        public int leadSilenceMs;
        public int introEndMs;
        public int outroStartMs;
        public int tailSilenceMs;
        public String waveform;       // base64, 1000 bytes
    }

    // ------------------------------------------------------------------ main

    public static Result analyze(float[] x) {
        Result r = new Result();
        int n = x.length;
        r.durationMs = (int) Math.round(n * 1000.0 / SR);
        if (n < SR) {                       // under a second: nothing to find
            r.waveform = waveform(x, 1000);
            r.tailSilenceMs = r.durationMs;
            r.outroStartMs = r.durationMs;
            return r;
        }

        // --- silence at the edges (93 ms blocks) ---
        int blk = 1024;
        int nb = n / blk;
        double[] blkDb = new double[nb];
        for (int b = 0; b < nb; b++) blkDb[b] = rmsDb(x, b * blk, blk);
        int first = 0;
        while (first < nb && blkDb[first] < SILENCE_DB) first++;
        int last = nb - 1;
        while (last > first && blkDb[last] < SILENCE_DB) last--;
        r.leadSilenceMs = first >= nb ? 0 : (int) Math.round(first * blk * 1000.0 / SR);
        r.tailSilenceMs = first >= nb ? r.durationMs : (int) Math.min(r.durationMs, Math.round((last + 1) * blk * 1000.0 / SR));

        r.waveform = waveform(x, 1000);

        // --- spectrum, onsets, chroma ---
        int frames = Math.max(0, (n - N) / HOP + 1);
        if (frames < 64) { r.introEndMs = r.leadSilenceMs; r.outroStartMs = r.tailSilenceMs; return r; }
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        int bins = N / 2 + 1;
        int maxBin = Math.min(bins - 1, (int) (4000.0 * N / SR));
        int lowBin = Math.max(2, (int) Math.round(150.0 * N / SR));
        int[] pcOf = new int[bins];
        double[] pcW = new double[bins];
        for (int k = 0; k < bins; k++) {
            double f = (double) k * SR / N;
            if (f < 55 || f > 2000) { pcOf[k] = -1; continue; }
            double midi = 69 + 12 * Math.log(f / 440.0) / Math.log(2);
            int pc = (int) Math.round(midi) % 12;
            pcOf[k] = (pc + 12) % 12;
            pcW[k] = 1.0 - Math.min(1.0, Math.abs(midi - Math.round(midi)) * 2); // weight bins near a note's centre
        }

        double[] flux = new double[frames];
        double[] lowFlux = new double[frames];
        double[][] chromaF = new double[frames][];
        double[] prev = new double[bins];
        double[] re = new double[N];
        double[] im = new double[N];
        double[] songChroma = new double[12];
        for (int t = 0; t < frames; t++) {
            int off = t * HOP;
            for (int i = 0; i < N; i++) { re[i] = x[off + i] * win[i]; im[i] = 0; }
            fft(re, im);
            double fl = 0, lf = 0;
            double[] ch = new double[12];
            for (int k = 1; k < bins; k++) {
                double mag = Math.hypot(re[k], im[k]);
                double lm = Math.log1p(100 * mag);
                double d = lm - prev[k];
                if (d > 0) { if (k <= maxBin) fl += d; if (k <= lowBin) lf += d; }
                prev[k] = lm;
                if (pcOf[k] >= 0) ch[pcOf[k]] += mag * pcW[k];
            }
            flux[t] = fl;
            lowFlux[t] = lf;
            chromaF[t] = ch;
            for (int c = 0; c < 12; c++) songChroma[c] += ch[c];
        }
        double[] env = normalize(subtractLocalMean(flux, (int) Math.round(FPS)));
        double[] lowEnv = normalize(subtractLocalMean(lowFlux, (int) Math.round(FPS)));

        // --- key ---
        String[] key = key(songChroma);
        r.key = key[0];
        r.camelot = key[1];

        // --- tempo ---
        double[] tempo = tempo(env);
        double periodFrames = tempo[0];
        r.bpmConfidence = tempo[1];
        double bpm = 60.0 * FPS / periodFrames;
        // Onset frame t covers samples t*HOP .. t*HOP+N. Log-flux catches a hit as
        // soon as it enters the window, near its far end (measured: 0.83 N).
        double frameCentre = 0.83 * N / SR;
        double beatSec = 60.0 / bpm;

        if (r.bpmConfidence >= 0.15) {
            r.bpm = Math.round(bpm * 100) / 100.0;
            double phase = bestPhase(env, periodFrames);
            double firstBeat = phase / FPS + frameCentre;
            double lead = r.leadSilenceMs / 1000.0;
            while (firstBeat - beatSec >= lead - 0.05) firstBeat -= beatSec;
            while (firstBeat < lead - 0.05) firstBeat += beatSec;
            r.beatOffsetMs = (int) Math.round(firstBeat * 1000);

            // downbeat: the beat-in-bar with the strongest kick + harmonic change
            int beatsTotal = (int) ((n / (double) SR - firstBeat) / beatSec);
            double[] lowAt = new double[Math.max(beatsTotal, 0)];
            double[][] beatChroma = new double[Math.max(beatsTotal, 0)][];
            for (int b = 0; b < beatsTotal; b++) {
                double tb = firstBeat + b * beatSec;
                lowAt[b] = sampleAt(lowEnv, (tb - frameCentre) * FPS);
                int f0 = (int) Math.max(0, Math.floor((tb - frameCentre) * FPS));
                int f1 = (int) Math.min(frames, Math.floor((tb + beatSec - frameCentre) * FPS));
                double[] c = new double[12];
                for (int f = f0; f < f1; f++) for (int k = 0; k < 12; k++) c[k] += chromaF[f][k];
                beatChroma[b] = c;
            }
            double[] score = new double[4];
            double lowMax = 1e-9, novMax = 1e-9;
            double[] nov = new double[Math.max(beatsTotal, 0)];
            for (int b = 1; b < beatsTotal; b++) nov[b] = 1 - cosine(beatChroma[b - 1], beatChroma[b]);
            for (int b = 0; b < beatsTotal; b++) { lowMax = Math.max(lowMax, lowAt[b]); novMax = Math.max(novMax, nov[b]); }
            for (int b = 0; b < beatsTotal; b++) score[b % 4] += lowAt[b] / lowMax + 1.2 * nov[b] / novMax;
            int j = 0;
            for (int k = 1; k < 4; k++) if (score[k] > score[j]) j = k;
            r.downbeatMs = (int) Math.round((firstBeat + j * beatSec) * 1000);
        }

        // --- intro / outro from loudness per bar (or per 2 s without a beat) ---
        double unit = r.bpm != null ? 4 * beatSec : 2.0;
        double start = r.bpm != null ? r.downbeatMs / 1000.0 : r.leadSilenceMs / 1000.0;
        while (start - unit >= r.leadSilenceMs / 1000.0 - 0.01) start -= unit;  // grid reaches back to the music's start
        double end = r.tailSilenceMs / 1000.0;
        int units = (int) Math.floor((end - start) / unit);
        if (units < 4) {
            r.introEndMs = r.leadSilenceMs;
            r.outroStartMs = r.tailSilenceMs;
            return r;
        }
        double[] lvl = new double[units];
        for (int u = 0; u < units; u++) {
            int s0 = (int) Math.round((start + u * unit) * SR);
            int len = (int) Math.round(unit * SR);
            lvl[u] = rmsDb(x, Math.max(0, s0), Math.min(len, n - Math.max(0, s0)));
        }
        double[] sorted = lvl.clone();
        Arrays.sort(sorted);
        double body = sorted[(int) Math.floor(sorted.length * 0.6)];
        double full = body - 3.0;
        int introUnit = 0;
        for (int u = 0; u + 1 < units; u++) { if (lvl[u] >= full && lvl[u + 1] >= full) { introUnit = u; break; } }
        int outroUnit = units;
        for (int u = units - 1; u >= 1; u--) { if (lvl[u] >= full && lvl[u - 1] >= full) { outroUnit = u + 1; break; } }
        double introEnd = start + introUnit * unit;
        double outroStart = start + outroUnit * unit;
        double halfway = (r.leadSilenceMs + (r.tailSilenceMs - r.leadSilenceMs) / 2.0) / 1000.0;
        if (outroStart < halfway) outroStart = Math.min(end, halfway);
        if (outroStart < end - 32 * unit / (r.bpm != null ? 1 : 2)) outroStart = end - 32 * unit / (r.bpm != null ? 1 : 2);
        r.introEndMs = (int) Math.round(Math.max(r.leadSilenceMs / 1000.0, introEnd) * 1000);
        r.outroStartMs = (int) Math.round(Math.min(end, outroStart) * 1000);
        return r;
    }

    // ------------------------------------------------------------------ tempo

    /** Returns {period in frames, confidence 0..1}. */
    static double[] tempo(double[] env) {
        int T = env.length;
        double minBpm = 55, maxBpm = 200;
        double lagMin = 60 * FPS / maxBpm, lagMax = 60 * FPS / minBpm;
        double step = 0.05;
        int L = (int) ((lagMax - lagMin) / step) + 1;
        double[] ac = new double[L];
        for (int i = 0; i < L; i++) ac[i] = autocorr(env, lagMin + i * step);
        double[] score = new double[L];
        double mean = 0;
        for (int i = 0; i < L; i++) {
            double lag = lagMin + i * step;
            double s = ac[i] + 0.5 * autocorr(env, 2 * lag) + 0.25 * autocorr(env, 4 * lag);
            double bpm = 60 * FPS / lag;
            double oct = Math.log(bpm / 115.0) / Math.log(2);
            s *= Math.exp(-0.5 * (oct / 0.9) * (oct / 0.9));   // prefer 80-160 BPM
            score[i] = s;
            mean += s;
        }
        mean /= L;
        double var = 0;
        int best = 0;
        for (int i = 0; i < L; i++) { var += (score[i] - mean) * (score[i] - mean); if (score[i] > score[best]) best = i; }
        double sd = Math.sqrt(var / L) + 1e-12;
        double z = (score[best] - mean) / sd;
        double conf = Math.max(0, Math.min(1, (z - 2.0) / 6.0));
        double period = lagMin + best * step;

        // refine: the grid that lines up with the most onsets over the whole song
        double bpm0 = 60 * FPS / period;
        double bestBpm = bpm0, bestFit = -1;
        for (double b = bpm0 - 1.0; b <= bpm0 + 1.0; b += 0.02) {
            double fit = gridFit(env, 60 * FPS / b);
            if (fit > bestFit) { bestFit = fit; bestBpm = b; }
        }
        double whole = Math.round(bestBpm);
        if (Math.abs(whole - bestBpm) <= 0.25 && gridFit(env, 60 * FPS / whole) >= bestFit * 0.985) bestBpm = whole;
        if (T < FPS * 8) conf *= 0.5;   // under 8 s: too short to trust
        return new double[] { 60 * FPS / bestBpm, conf };
    }

    static double autocorr(double[] e, double lag) {
        int T = e.length;
        int li = (int) Math.floor(lag);
        double fr = lag - li;
        double s = 0;
        for (int t = 0; t + li + 1 < T; t++) s += e[t] * (e[t + li] * (1 - fr) + e[t + li + 1] * fr);
        return s / Math.max(1, T - li);
    }

    static double gridFit(double[] env, double period) {
        return combSum(env, period, bestPhase(env, period));
    }

    static double bestPhase(double[] env, double period) {
        double best = 0, bestS = -1;
        for (double p = 0; p < period; p += 0.25) {
            double s = combSum(env, period, p);
            if (s > bestS) { bestS = s; best = p; }
        }
        return best;
    }

    static double combSum(double[] env, double period, double phase) {
        double s = 0;
        int c = 0;
        for (double t = phase; t < env.length - 1; t += period) { s += sampleAt(env, t); c++; }
        return c == 0 ? 0 : s / c;
    }

    static double sampleAt(double[] a, double t) {
        if (t < 0 || a.length == 0) return 0;
        int i = (int) Math.floor(t);
        if (i >= a.length - 1) return a[a.length - 1];
        double f = t - i;
        return a[i] * (1 - f) + a[i + 1] * f;
    }

    // ------------------------------------------------------------------ key

    static final double[] MAJOR = { 6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88 };
    static final double[] MINOR = { 6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17 };
    static final String[] MAJOR_NAME = { "C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B" };
    static final String[] MINOR_NAME = { "C", "C#", "D", "Eb", "E", "F", "F#", "G", "G#", "A", "Bb", "B" };
    static final String[] MAJOR_CAMELOT = { "8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B" };
    static final String[] MINOR_CAMELOT = { "5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A", "8A", "3A", "10A" };

    /** {"A minor", "8A"}, or {null, null} for silence. */
    static String[] key(double[] chroma) {
        double total = 0;
        for (double c : chroma) total += c;
        if (total <= 1e-9) return new String[] { null, null };
        double best = -2; int bestPc = 0; boolean minor = false;
        for (int pc = 0; pc < 12; pc++) {
            double[] rot = new double[12];
            for (int i = 0; i < 12; i++) rot[i] = chroma[(i + pc) % 12];
            double maj = pearson(rot, MAJOR), min = pearson(rot, MINOR);
            if (maj > best) { best = maj; bestPc = pc; minor = false; }
            if (min > best) { best = min; bestPc = pc; minor = true; }
        }
        return minor
            ? new String[] { MINOR_NAME[bestPc] + " minor", MINOR_CAMELOT[bestPc] }
            : new String[] { MAJOR_NAME[bestPc] + " major", MAJOR_CAMELOT[bestPc] };
    }

    static double pearson(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length; mb /= b.length;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) { num += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma) * (a[i] - ma); db += (b[i] - mb) * (b[i] - mb); }
        return num / Math.sqrt(da * db + 1e-12);
    }

    static double cosine(double[] a, double[] b) {
        double d = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return d / Math.sqrt(na * nb + 1e-12);
    }

    // ------------------------------------------------------------------ helpers

    static double rmsDb(float[] x, int off, int len) {
        if (len <= 0) return -120;
        double s = 0;
        for (int i = off; i < off + len && i < x.length; i++) s += (double) x[i] * x[i];
        double rms = Math.sqrt(s / len);
        return rms <= 1e-9 ? -120 : 20 * Math.log10(rms);
    }

    /** 1000 peaks, gently compressed so quiet passages stay visible, as base64. */
    static String waveform(float[] x, int points) {
        byte[] out = new byte[points];
        double[] peak = new double[points];
        double gmax = 1e-9;
        for (int p = 0; p < points; p++) {
            int a = (int) ((long) p * x.length / points), b = (int) ((long) (p + 1) * x.length / points);
            double m = 0;
            for (int i = a; i < b; i++) m = Math.max(m, Math.abs(x[i]));
            peak[p] = m;
            gmax = Math.max(gmax, m);
        }
        for (int p = 0; p < points; p++) out[p] = (byte) Math.round(255 * Math.pow(peak[p] / gmax, 0.8));
        return Base64.getEncoder().encodeToString(out);
    }

    static double[] subtractLocalMean(double[] a, int half) {
        double[] out = new double[a.length];
        double[] pre = new double[a.length + 1];
        for (int i = 0; i < a.length; i++) pre[i + 1] = pre[i] + a[i];
        for (int i = 0; i < a.length; i++) {
            int lo = Math.max(0, i - half), hi = Math.min(a.length, i + half + 1);
            double mean = (pre[hi] - pre[lo]) / (hi - lo);
            out[i] = Math.max(0, a[i] - mean);
        }
        return out;
    }

    static double[] normalize(double[] a) {
        double m = 1e-12;
        for (double v : a) m = Math.max(m, v);
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] / m;
        return out;
    }

    /** In-place radix-2 FFT. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
}
