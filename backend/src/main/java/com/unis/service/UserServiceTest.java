package com.unis.service;

public class UserServiceTest {

}




// package com.unis.service;

// import com.unis.entity.User;
// import com.unis.repository.UserRepository;
// import com.unis.repository.SupporterRepository;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.InjectMocks;
// import org.mockito.Mock;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.springframework.security.crypto.password.PasswordEncoder;

// import java.util.Optional;
// import java.util.UUID;

// import static org.junit.jupiter.api.Assertions.*;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.Mockito.*;

// @ExtendWith(MockitoExtension.class)
// class UserServiceTest {

//     @InjectMocks
//     private UserService userService;

//     @Mock
//     private UserRepository userRepository;

//     @Mock
//     private SupporterRepository supporterRepository;

//     @Mock
//     private PasswordEncoder passwordEncoder;

//     private User testUser;
//     private UUID testUserId;
//     private UUID testJurisdictionId;
//     private UUID testArtistId;

//     @BeforeEach
//     void setUp() {
//         testUserId = UUID.randomUUID();
//         testJurisdictionId = UUID.randomUUID();
//         testArtistId = UUID.randomUUID();

//         testUser = User.builder()
//             .userId(testUserId)
//             .username("testuser")
//             .email("test@example.com")
//             .passwordHash("hashedpass")
//             .role(User.Role.listener)
//             .jurisdictionId(testJurisdictionId)
//             .build();
//     }

//     @Test
//     void testRegister() {
//         // Mock supported artist
//         User mockArtist = User.builder()
//             .userId(testArtistId)
//             .role(User.Role.artist)
//             .build();
//         when(userRepository.findById(testArtistId)).thenReturn(Optional.of(mockArtist));
//         when(userRepository.save(any(User.class))).thenReturn(testUser);
//         when(supporterRepository.save(any())).thenReturn(null);
//         when(passwordEncoder.encode("rawpass")).thenReturn("hashedrawpass");

//         User newUser = User.builder()
//             .username("newuser")
//             .email("new@example.com")
//             .passwordHash("rawpass")
//             .role(User.Role.listener)
//             .build();

//         User registered = userService.register(newUser, testArtistId);

//         assertNotNull(registered);
//         assertEquals("newuser", registered.getUsername());
//         verify(passwordEncoder).encode("rawpass");
//         verify(userRepository).save(any(User.class));
//         verify(supporterRepository).save(any());
//     }

//     @Test
//     void testRegisterInvalidArtist() {
//         when(userRepository.findById(testArtistId)).thenReturn(Optional.empty());

//         User newUser = User.builder()
//             .username("newuser")
//             .email("new@example.com")
//             .passwordHash("rawpass")
//             .role(User.Role.listener)
//             .build();

//         assertThrows(RuntimeException.class, () -> userService.register(newUser, testArtistId));
//     }

//     @Test
//     void testGetProfile() {
//         User mockProfile = User.builder()
//             .userId(testUserId)
//             .username("testuser")
//             .build();
//         when(userRepository.findByIdWithJurisdiction(testUserId)).thenReturn(mockProfile);

//         User profile = userService.getProfile(testUserId);

//         assertNotNull(profile);
//         assertEquals("testuser", profile.getUsername());
//     }

//     @Test
//     void testUpdatePhoto() {
//         when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
//         when(userRepository.save(testUser)).thenReturn(testUser);

//         User updated = userService.updatePhoto(testUserId, "/uploads/photo.jpg");

//         assertNotNull(updated);
//         assertEquals("/uploads/photo.jpg", updated.getPhotoUrl());
//         verify(userRepository).save(testUser);
//     }

//     @Test
//     void testUpdatePassword() {
//         String oldPass = "oldpass";
//         String newPass = "newpass";
//         when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
//         when(passwordEncoder.matches(oldPass, testUser.getPasswordHash())).thenReturn(true);
//         when(passwordEncoder.encode(newPass)).thenReturn("hashednewpass");
//         when(userRepository.save(testUser)).thenReturn(testUser);

//         User updated = userService.updatePassword(testUserId, oldPass, newPass);

//         assertNotNull(updated);
//         verify(passwordEncoder).matches(oldPass, testUser.getPasswordHash());
//         verify(passwordEncoder).encode(newPass);
//         verify(userRepository).save(testUser);
//     }
// }