package com.unis.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.unis.entity.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps people's private account details out of public API responses.
 *
 * Many endpoints return the User entity itself — directly (public profiles,
 * the artist list) or inside other objects (every song carries its artist).
 * Before this, every one of those responses included the person's login email
 * and their unsubscribe link token, so anyone could collect the email address
 * of every artist on Unis from one public URL.
 *
 * Now, when a User is turned into JSON:
 *   - PRIVATE fields appear only to the account itself or to staff
 *     (moderator, admin, super admin). Everyone else simply doesn't get them.
 *   - SECRET fields never appear at all.
 *
 * Nothing in the database or in server code changes; only what the API sends.
 * Spring Boot picks up this Module bean and adds it to the app's ObjectMapper.
 */
@Configuration
public class UserPrivacyJacksonConfig {

    /** Shown only to the account owner and to staff. */
    static final Set<String> PRIVATE = Set.of(
        "email",
        "gender",
        "emailVerified",
        "phoneVerified",
        "emailNotifications",
        "publicProfile",
        "showVoteHistory",
        "explicitContentEnabled",
        "allowMessagesFromAnyone",
        "messagePrivacy",
        "supportersSkipRequests",
        "showActivityStatus",
        "sendReadReceipts",
        "filterSpamRequests",
        "pendingSupportedArtistId",
        "pendingSupportedArtistSince",
        "stripeOnboardingComplete",
        "excludeFromMetrics"
    );

    /** Never sent to anyone. */
    static final Set<String> SECRET = Set.of(
        "unsubscribeToken"
    );

    @Bean
    public Module userPrivacyModule() {
        SimpleModule module = new SimpleModule("UserPrivacy");
        module.setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                             BeanDescription beanDesc,
                                                             List<BeanPropertyWriter> props) {
                // isAssignableFrom also covers Hibernate's lazy-loading proxies of User.
                if (!User.class.isAssignableFrom(beanDesc.getBeanClass())) return props;
                List<BeanPropertyWriter> out = new ArrayList<>(props.size());
                for (BeanPropertyWriter p : props) {
                    if (SECRET.contains(p.getName())) continue;
                    out.add(PRIVATE.contains(p.getName()) ? new OwnerOrStaffOnly(p) : p);
                }
                return out;
            }
        });
        return module;
    }

    /** Writes the field only when the viewer is that user or staff. */
    static final class OwnerOrStaffOnly extends BeanPropertyWriter {
        OwnerOrStaffOnly(BeanPropertyWriter base) {
            super(base);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            if (mayView(bean)) super.serializeAsField(bean, gen, prov);
        }

        @Override
        public void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            if (mayView(bean)) super.serializeAsElement(bean, gen, prov);
            else serializeAsPlaceholder(bean, gen, prov);
        }
    }

    static boolean mayView(Object bean) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;

        boolean staff = auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_MODERATOR".equals(a.getAuthority()));
        if (staff) return true;

        Object credentials = auth.getCredentials();   // the signed-in user's id (JwtRequestFilter)
        if (credentials == null || !(bean instanceof User user)) return false;
        UUID owner = user.getUserId();
        return owner != null && owner.toString().equals(credentials.toString());
    }
}
