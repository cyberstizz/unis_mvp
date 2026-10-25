package com.unis.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Sends every LocalDateTime to clients marked as UTC.
 *
 * The server runs on UTC (UnisApplication sets the JVM default), so every
 * LocalDateTime the app stores is a UTC clock reading. Jackson's default
 * output drops the zone ("2026-10-25T20:56:00.123456"), and browsers read a
 * zone-less date-time as the viewer's LOCAL time — so a message sent at
 * 4:56 PM in New York showed as 8:56 PM, and "x minutes ago" counters read
 * "now" for hours (messages, comments, anything that does new Date(...)).
 *
 * With this module the same value goes out as "2026-10-25T20:56:00.123456Z",
 * which every browser and the React Native app parse correctly.
 *
 * Reading is unchanged: Jackson's LocalDateTime deserializer accepts a
 * trailing "Z", and @DateTimeFormat(iso = DATE_TIME) query params (e.g. the
 * thread's ?before= cursor) accept it too.
 *
 * Spring Boot adds Module beans to the app's ObjectMapper after its built-in
 * java.time module, so this serializer takes precedence — for REST responses
 * and for the STOMP (/user/queue/...) payloads alike.
 */
@Configuration
public class UtcDateTimeJacksonConfig {

    private static final DateTimeFormatter UTC_ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Bean
    public Module utcLocalDateTimeModule() {
        SimpleModule module = new SimpleModule("UnisUtcLocalDateTime");
        module.addSerializer(LocalDateTime.class, new UtcLocalDateTimeSerializer());
        return module;
    }

    static final class UtcLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.atOffset(ZoneOffset.UTC).format(UTC_ISO));
        }

        @Override
        public Class<LocalDateTime> handledType() {
            return LocalDateTime.class;
        }
    }
}
