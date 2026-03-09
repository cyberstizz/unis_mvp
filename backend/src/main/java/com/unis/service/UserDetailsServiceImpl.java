package com.unis.service;

import com.unis.entity.AdminRole;
import com.unis.entity.User;
import com.unis.repository.AdminRoleRepository;
import com.unis.repository.AccountSuspensionRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminRoleRepository adminRoleRepository;

    @Autowired
    private AccountSuspensionRepository accountSuspensionRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // C5 FIX: uses findActiveByEmail — soft-deleted users cannot authenticate
        User user = userRepository.findActiveByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + username));

        // Suspension check: block suspended users from authenticating
        if (accountSuspensionRepository.isUserSuspended(user.getUserId())) {
            throw new UsernameNotFoundException("Account is suspended");
        }

        // Load admin roles for GrantedAuthority
        List<GrantedAuthority> authorities = new ArrayList<>();
        Optional<AdminRole> adminRole = adminRoleRepository.findByUserId(user.getUserId());
        if (adminRole.isPresent()) {
            switch (adminRole.get().getRoleLevel()) {
                case "super_admin":
                    authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    authorities.add(new SimpleGrantedAuthority("ROLE_MODERATOR"));
                    break;
                case "admin":
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    authorities.add(new SimpleGrantedAuthority("ROLE_MODERATOR"));
                    break;
                case "moderator":
                    authorities.add(new SimpleGrantedAuthority("ROLE_MODERATOR"));
                    break;
            }
        }

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash(),
                authorities
        );
    }
}