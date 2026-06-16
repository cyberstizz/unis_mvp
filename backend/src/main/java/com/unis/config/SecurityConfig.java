package com.unis.config;

import com.unis.config.JwtRequestFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtRequestFilter jwtRequestFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOriginPattern("http://localhost:5173");
        configuration.addAllowedOriginPattern("https://unisprototypetwo.netlify.app");
        configuration.addAllowedOriginPattern("https://www.unismusic.com");
        configuration.addAllowedOriginPattern("https://unismusic.com");
        configuration.addAllowedOriginPattern("http://localhost:3000");
        configuration.addAllowedOriginPattern("http://127.0.0.1:5173");
        configuration.addAllowedOriginPattern("http://127.0.0.1:3000");
        configuration.addAllowedOriginPattern("http://192.168.*.*");
        configuration.addAllowedOriginPattern("https://*.netlify.app");
        configuration.setAllowCredentials(true);
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                // ===== PREFLIGHT =====
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // ===== ADMIN-ONLY — C2 + C3 =====
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/awards/recompute-all").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/awards/compute").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/awards/cron/manual").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/vote/awards/compute").hasRole("ADMIN")

                // ===== ADMIN DASHBOARD ENDPOINTS =====
                .requestMatchers("/api/v1/admin/roles/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/admin/audit/**").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/users/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/admin/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/dmca/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/dmca/*/takedown").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/songs/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/videos/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/admin/analytics/**").hasRole("MODERATOR")
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/dmca/**").hasRole("MODERATOR")
                .requestMatchers("/api/v1/admin/comments/**").hasRole("MODERATOR")

                // ===== PLAYLIST — ADMIN =====
                .requestMatchers(HttpMethod.POST, "/api/v1/playlists/official").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/playlists/official/*/sync").hasRole("ADMIN")

                // ===== PLAYLIST — AUTHENTICATED (must come BEFORE public wildcard) =====
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/mine").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/following").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/blocked-songs").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/playlists/cover").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/playlists/*/cover").authenticated()

                // ===== PLAYLIST — PUBLIC DISCOVERY =====
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/discover").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/community/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/official").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/search").permitAll()
                // Individual playlist + sub-resources (visibility enforced in service layer)
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/*/pending").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/*/activity").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/playlists/*").permitAll()

                // Public endpoints (no auth required)
                .requestMatchers(HttpMethod.POST, "/api/v1/dmca/submit").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/activity/track").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/verify-email").permitAll()        // ★
                .requestMatchers(HttpMethod.GET,  "/api/auth/verify-email").permitAll()        // ★
                .requestMatchers(HttpMethod.POST, "/api/auth/resend-verification").permitAll() // ★
                .requestMatchers(HttpMethod.POST, "/api/v1/media/signup-song").permitAll()     // ★

                // ===== AUTHENTICATED MUTATIONS — C1 + C6 =====
                .requestMatchers(HttpMethod.POST, "/api/v1/media/song").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/media/video").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/media/song/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/media/video/**").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/v1/media/song/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/media/song/*/like").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/media/song/*/like").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/vote/submit").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/comments").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/v1/comments/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/earnings/track-view").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/earnings/my-summary").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/earnings/my-referrals").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/earnings/my-history").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/artist-analytics/**").authenticated()   // ← add this line

                // ===== AUTHENTICATED PROFILE MUTATIONS — C4 =====
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/profile/*/photo").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/profile/*/bio").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/profile/*/password").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/profile/*").authenticated()

                // ===== PUBLIC ENDPOINTS =====
                .requestMatchers(HttpMethod.POST, "/auth/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/v1/users/register").permitAll()
                .requestMatchers("/api/v1/users/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll()
                .requestMatchers("/api/v1/users/default-song").permitAll()
                .requestMatchers("/api/v1/users/artists/active").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/profile").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/profile/photo").permitAll()
                .requestMatchers(HttpMethod.PATCH, "/api/v1/users/profile/photo").permitAll()
                .requestMatchers("/api/v1/users/me").permitAll()
                .requestMatchers("/api/v1/jurisdictions/by-location").permitAll()
                .requestMatchers("/api/v1/users/validate-referral/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/unsubscribe").permitAll()
                .requestMatchers("/api/v1/users/check-email").permitAll()
                .requestMatchers("/api/v1/users/check-username").permitAll()
                .requestMatchers("/api/v1/users/*/default-song").permitAll()
                .requestMatchers("/api/v1/waitlist/**").permitAll()

                // ===== PUBLIC READ-ONLY — Guest browsing =====
                // Media discovery & playback
                .requestMatchers(HttpMethod.GET, "/api/v1/media/trending/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/new").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/song/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/song/*/lyrics").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/song/*/likes/count").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/song/*/is-liked").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/songs/artist/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/songs/jurisdiction/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/videos/artist/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/media/videos/jurisdiction/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/media/song/*/play").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/media/play/complete").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/media/video/*/play").permitAll()
                .requestMatchers(HttpMethod.PUT, "/api/v1/songs/*/download-settings").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/songs/*/purchase").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/songs/*/purchase/confirm").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/songs/*/download").authenticated()

                // User profiles & artists (read-only)
                .requestMatchers(HttpMethod.GET, "/api/v1/users/profile/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/artist/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/artist/top").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/*/supporters/count").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/*/followers/count").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/*/total-plays").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/*/total-votes").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/*/total-likes").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/songs/my-purchases").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/songs/my-sales").authenticated()

                // Awards & voting (read-only)
                .requestMatchers(HttpMethod.GET, "/api/v1/awards/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/vote/nominees").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/vote/leaderboards").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/vote/results").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/vote/total/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/vote/check-eligibility").permitAll()

                // Jurisdictions (all read-only)
                .requestMatchers(HttpMethod.GET, "/api/v1/jurisdictions/**").permitAll()

                // Comments (read-only)
                .requestMatchers(HttpMethod.GET, "/api/v1/comments/**").permitAll()

                // ===== CATCH-ALL: everything else under /api/v1 requires auth =====
                .requestMatchers("/api/v1/**").authenticated()
                .requestMatchers("/uploads/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/error/**").permitAll()
                .anyRequest().authenticated()
            )
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}