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

                // Super Admin only
                .requestMatchers("/api/v1/admin/roles/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/admin/audit/**").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/users/**").hasRole("SUPER_ADMIN")

                // Admin + Super Admin
                .requestMatchers("/api/v1/admin/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/dmca/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/dmca/*/takedown").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/songs/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/videos/**").hasRole("ADMIN")

                // All admin tiers (moderator and above)
                .requestMatchers("/api/v1/admin/analytics/**").hasRole("MODERATOR")
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/dmca/**").hasRole("MODERATOR")
                .requestMatchers("/api/v1/admin/comments/**").hasRole("MODERATOR")

                // Public endpoints (no auth required)
                .requestMatchers(HttpMethod.POST, "/api/v1/dmca/submit").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/activity/track").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll()

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
                .requestMatchers(HttpMethod.DELETE, "/api/v1/comments/**").authenticated()

                // ===== AUTHENTICATED PROFILE MUTATIONS — C4 =====
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/profile/*/photo").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/profile/*/bio").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/profile/*/password").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/profile/*").authenticated()

                // ===== PUBLIC ENDPOINTS =====
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/v1/users/register").permitAll()
                .requestMatchers("/api/v1/users/login").permitAll()
                .requestMatchers("/api/v1/users/default-song").permitAll()
                .requestMatchers("/api/v1/users/artists/active").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/profile").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/users/profile/photo").permitAll()
                .requestMatchers(HttpMethod.PATCH, "/api/v1/users/profile/photo").permitAll()                
                .requestMatchers("/api/v1/users/me").permitAll()
                .requestMatchers("/api/v1/jurisdictions/by-location").permitAll()
                .requestMatchers("/api/v1/users/validate-referral/**").permitAll()
                .requestMatchers("/api/v1/users/check-email").permitAll()
                .requestMatchers("/api/v1/users/check-username").permitAll()
                .requestMatchers("/api/v1/users/*/default-song").permitAll()
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