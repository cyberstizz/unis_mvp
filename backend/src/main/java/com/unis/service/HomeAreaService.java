package com.unis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Find my area" on the sign-up's Your Hood step: is this street address in
 * Uptown Harlem, Downtown Harlem, or outside Harlem?
 *
 * WHY IT'S HERE: the web sign-up used to send the address straight from the
 * browser to nominatim.openstreetmap.org. That server is run by volunteers;
 * its usage policy caps an app's users together at one request a second, asks
 * apps to identify themselves (browsers can't set the header), and asks that
 * no personal data be sent. A home address is personal data, and a launch-day
 * rush at a housing project could get the finder blocked.
 *
 * NOW: the server asks the US Census Bureau's geocoder, which exists to turn
 * US street addresses into coordinates, needs no key and costs nothing.
 * https://geocoding.geo.census.gov/geocoder/Geocoding_Services_API.html
 * Answers are cached in memory under a hash of the address (the address
 * itself is never stored or logged), and outgoing calls are spaced out so a
 * rush stays polite. If the geocoder is down or slow, the sign-up says so and
 * the person picks their area by hand (then the honesty check applies).
 *
 * The Harlem rule is the same box and 125th Street dividing line the sign-up
 * wizard used in the browser, moved here unchanged.
 */
@Service
public class HomeAreaService {

    private static final Logger log = LoggerFactory.getLogger(HomeAreaService.class);

    // Same numbers createAccountWizard.jsx used.
    private static final double NORTH = 40.8282;
    private static final double SOUTH = 40.7967;
    private static final double EAST = -73.9262;
    private static final double WEST = -73.9595;
    private static final double DIVIDING_LINE = 40.8095;

    private static final String GEOCODER =
            "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress?benchmark=Public_AR_Current&format=json&address=";

    /** At most a few outgoing calls a second, all users together. */
    private static final long MIN_GAP_MS = 250;
    /** Past this wait, answer "busy" instead of holding the request. */
    private static final long MAX_WAIT_MS = 4000;
    private static final int CACHE_SIZE = 5000;

    public record Result(boolean found, boolean inHarlem, String area, String areaName) {}

    /** The geocoder couldn't be reached, timed out, or the line was too busy. */
    public static class LookupUnavailable extends RuntimeException {
        public LookupUnavailable(String why) { super(why); }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Result> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private final Object gate = new Object();
    private long nextSlot = 0;

    public Result lookup(String rawAddress) {
        String address = rawAddress == null ? "" : rawAddress.trim().replaceAll("\\s+", " ");
        if (address.length() < 5) {
            throw new IllegalArgumentException("Enter your street address, like 123 W 125th St, New York, NY.");
        }
        if (address.length() > 200) {
            throw new IllegalArgumentException("That address is too long.");
        }

        String key = hash(address.toLowerCase());
        synchronized (cache) {
            Result hit = cache.get(key);
            if (hit != null) return hit;
        }

        waitForSlot();
        Result result = ask(address);
        synchronized (cache) {
            cache.put(key, result);
        }
        return result;
    }

    private void waitForSlot() {
        long wait;
        synchronized (gate) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextSlot);
            wait = slot - now;
            if (wait > MAX_WAIT_MS) {
                log.warn("[HomeArea] action=lookup status=busy");
                throw new LookupUnavailable("busy");
            }
            nextSlot = slot + MIN_GAP_MS;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LookupUnavailable("interrupted");
            }
        }
    }

    private Result ask(String address) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GEOCODER + URLEncoder.encode(address, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "UnisMusic/1.0 (+https://unismusic.com)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("[HomeArea] action=lookup status=http_{}", res.statusCode());
                throw new LookupUnavailable("http " + res.statusCode());
            }
            JsonNode matches = mapper.readTree(res.body()).path("result").path("addressMatches");
            if (!matches.isArray() || matches.isEmpty()) {
                log.info("[HomeArea] action=lookup status=no_match");
                return new Result(false, false, null, null);
            }
            JsonNode c = matches.get(0).path("coordinates");
            if (!c.has("x") || !c.has("y")) {
                return new Result(false, false, null, null);
            }
            Result r = classify(c.path("y").asDouble(), c.path("x").asDouble());
            log.info("[HomeArea] action=lookup status=ok in_harlem={}", r.inHarlem());
            return r;
        } catch (LookupUnavailable e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LookupUnavailable("interrupted");
        } catch (Exception e) {
            log.warn("[HomeArea] action=lookup status=error err={}", e.getClass().getSimpleName());
            throw new LookupUnavailable(e.getClass().getSimpleName());
        }
    }

    static Result classify(double lat, double lon) {
        if (lat < SOUTH || lat > NORTH || lon < WEST || lon > EAST) {
            return new Result(true, false, null, null);
        }
        return lat >= DIVIDING_LINE
                ? new Result(true, true, "uptown-harlem", "Uptown Harlem")
                : new Result(true, true, "downtown-harlem", "Downtown Harlem");
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
