package com.unis.config;

import com.unis.config.JwtRequestFilter;  // Your filter (swap if JwtRequestFilter)
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
    private JwtRequestFilter jwtRequestFilter;  // Your filter

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {  // Original: Your CORS bean
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOriginPattern("http://localhost:5173");  // Add Vite port
        configuration.addAllowedOriginPattern("http://localhost:3000");  // Backup
        configuration.addAllowedOriginPattern("http://127.0.0.1:5173");
        configuration.addAllowedOriginPattern("http://127.0.0.1:3000");
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
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()  // New: Permit OPTIONS preflight
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/v1/users/register").permitAll()  // Open register
                .requestMatchers("/api/v1/users/login").permitAll()  // Open login
                .requestMatchers("/api/v1/**").authenticated()  // Protect rest
                .requestMatchers("/uploads/**").permitAll()  // Protect rest
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/error/**").permitAll()
                .anyRequest().authenticated()
            )
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))  // Original: Wire CORS bean
            .csrf(csrf -> csrf.disable())  // Disable CSRF
            .httpBasic(basic -> basic.disable())  // Disable basic auth
            .formLogin(form -> form.disable()) // Disable form login
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);  // Original: Add JWT filter

        return http.build();
    }
}