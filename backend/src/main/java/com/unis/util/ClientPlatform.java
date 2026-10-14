package com.unis.util;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Which app a request came from: "web", "ios" or "android".
 *
 * The web app and the mobile app send an X-Unis-Platform header with every
 * request. Older app builds don't, and then this returns null ("unknown" in
 * the metrics). Any other value is ignored rather than stored.
 */
public final class ClientPlatform {

    public static final String HEADER = "X-Unis-Platform";

    private ClientPlatform() {}

    /** The platform of the request being handled, or null outside a request. */
    public static String current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return normalize(servletAttrs.getRequest().getHeader(HEADER));
        }
        return null;
    }

    public static String normalize(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase()) {
            case "web" -> "web";
            case "ios" -> "ios";
            case "android" -> "android";
            default -> null;
        };
    }
}
