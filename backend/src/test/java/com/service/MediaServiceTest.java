package com.service;

import com.unis.dto.SongUploadRequest;
import com.unis.dto.VideoUploadRequest;
import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.entity.SongPlay;
import com.unis.entity.VideoPlay;
import com.unis.entity.User;
import com.unis.entity.Genre;
import com.unis.repository.SongRepository;
import com.unis.repository.VideoRepository;
import com.unis.repository.SongPlayRepository;
import com.unis.repository.VideoPlayRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.GenreRepository;
import com.unis.service.FileStorageService;
import com.unis.service.MediaService;
import com.unis.service.ScoreUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @InjectMocks
    private MediaService mediaService;

    @Mock
    private SongRepository songRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private SongPlayRepository songPlayRepository;

    @Mock
    private VideoPlayRepository videoPlayRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GenreRepository genreRepository;

    @Mock
    private ScoreUpdateService scoreUpdateService;

    @Mock
    private FileStorageService fileStorageService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private UUID testSongId;
    private UUID testVideoId;
    private UUID testUserId;
    private UUID testGenreId;
    private UUID testJurisdictionId;


    @BeforeEach
    void setUp() {
        testSongId = UUID.randomUUID();
        testVideoId = UUID.randomUUID();
        testUserId = UUID.randomUUID();
        testGenreId = UUID.randomUUID();
        testJurisdictionId = UUID.randomUUID();
    }

    @Test
    void testAddSong() throws Exception {
        // Mock request JSON
        String songJson = "{\"title\":\"Test Song\",\"genreId\":\"" + testGenreId + "\",\"artistId\":\"" + testUserId + "\",\"description\":\"Test\",\"duration\":180}";
        MultipartFile mockFile = new MockMultipartFile("file", "test.mp3", "audio/mpeg", "test content".getBytes());
        MultipartFile mockFile2 = new MockMultipartFile("file", "test.jpg", "jpg", "test image".getBytes());
        // Mock lookups
        User mockArtist = User.builder().userId(testUserId).build();
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(mockArtist));
        Genre mockGenre = Genre.builder().genreId(testGenreId).build();
        when(genreRepository.findById(testGenreId)).thenReturn(Optional.of(mockGenre));
        when(fileStorageService.storeFile(mockFile)).thenReturn("/uploads/test.mp3");
        Song mockSaved = Song.builder().songId(testSongId).title("Test Song").build();
        when(songRepository.save(any(Song.class))).thenReturn(mockSaved);

        Song result = mediaService.addSong(songJson, mockFile, mockFile2);

        assertNotNull(result);
        assertEquals("Test Song", result.getTitle());
        verify(fileStorageService).storeFile(mockFile);
        verify(songRepository).save(any(Song.class));
    }

    @Test
    void testAddVideo() throws Exception {
        // Mock request JSON
        String videoJson = "{\"title\":\"Test Video\",\"genreId\":\"" + testGenreId + "\",\"artistId\":\"" + testUserId + "\",\"description\":\"Test\",\"duration\":300}";
        MultipartFile mockFile = new MockMultipartFile("file", "test.mp4", "video/mp4", "test content".getBytes());
        MultipartFile mockFile2 = new MockMultipartFile("file", "test.jpg", "jpg", "test image".getBytes());
        // Mock lookups
        User mockArtist = User.builder().userId(testUserId).build();
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(mockArtist));
        Genre mockGenre = Genre.builder().genreId(testGenreId).build();
        when(genreRepository.findById(testGenreId)).thenReturn(Optional.of(mockGenre));
        when(fileStorageService.storeFile(mockFile)).thenReturn("/uploads/test.mp4");
        Video mockSaved = Video.builder().videoId(testVideoId).title("Test Video").build();
        when(videoRepository.save(any(Video.class))).thenReturn(mockSaved);

        Video result = mediaService.addVideo(videoJson, mockFile, mockFile2);

        assertNotNull(result);
        assertEquals("Test Video", result.getTitle());
        verify(fileStorageService).storeFile(mockFile);
        verify(videoRepository).save(any(Video.class));
    }

    @Test
    void testDeleteSong() {
        mediaService.deleteSong(testSongId);

        verify(songRepository).deleteById(testSongId);
    }

    @Test
    void testDeleteVideo() {
        mediaService.deleteVideo(testVideoId);

        verify(videoRepository).deleteById(testVideoId);
    }

    @Test
    void testPlaySong() {
        User mockUser = User.builder().userId(testUserId).build();
        Genre mockGenre = Genre.builder().genreId(testGenreId).build();
        Song mockSong = Song.builder().songId(testSongId).title("Test Song").genre(mockGenre).build();
        when(songRepository.findById(testSongId)).thenReturn(Optional.of(mockSong));
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(mockUser));

        mediaService.playSong(testSongId, testUserId);

        verify(songPlayRepository).save(any(SongPlay.class));
        verify(scoreUpdateService).onPlay(testUserId, testSongId, "song");
    }

    @Test
    void testPlayVideo() {
        User mockUser = User.builder().userId(testUserId).build();
        Genre mockGenre = Genre.builder().genreId(testGenreId).build();
        Video mockVideo = Video.builder().videoId(testVideoId).title("Test Video").genre(mockGenre).build();
        when(videoRepository.findById(testVideoId)).thenReturn(Optional.of(mockVideo));
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(mockUser));

        mediaService.playVideo(testVideoId, testUserId);

        verify(videoPlayRepository).save(any(VideoPlay.class));
        verify(scoreUpdateService).onPlay(testUserId, testVideoId, "video");
    }

    @Test
    void testGetTopSongsByJurisdiction() {
        List<Song> mockSongs = List.of(Song.builder().songId(UUID.randomUUID()).title("Top Song").build());
        when(songRepository.findTopByJurisdictionWithHierarchy(testJurisdictionId, 3)).thenReturn(mockSongs);

        List<Song> result = mediaService.getTopSongsByJurisdiction(testJurisdictionId, 3);

        assertEquals(1, result.size());
        verify(songRepository).findTopByJurisdictionWithHierarchy(testJurisdictionId, 3);
    }

    @Test
    void testGetTopVideosByJurisdiction() {
        List<Video> mockVideos = List.of(Video.builder().videoId(UUID.randomUUID()).title("Top Video").build());
        when(videoRepository.findTopByJurisdictionWithHierarchy(testJurisdictionId, 3)).thenReturn(mockVideos);

        List<Video> result = mediaService.getTopVideosByJurisdiction(testJurisdictionId, 3);

        assertEquals(1, result.size());
        verify(videoRepository).findTopByJurisdictionWithHierarchy(testJurisdictionId, 3);
    }

    @Test
    void testGetSongsByArtist() {
        List<Song> mockSongs = List.of(Song.builder().songId(UUID.randomUUID()).title("Artist Song").build());
        when(songRepository.findByArtistId(testUserId)).thenReturn(mockSongs);

        List<Song> result = mediaService.getSongsByArtist(testUserId);

        assertEquals(1, result.size());
        verify(songRepository).findByArtistId(testUserId);
    }

    @Test
    void testGetVideosByArtist() {
        List<Video> mockVideos = List.of(Video.builder().videoId(UUID.randomUUID()).title("Artist Video").build());
        when(videoRepository.findByArtistId(testUserId)).thenReturn(mockVideos);

        List<Video> result = mediaService.getVideosByArtist(testUserId);

        assertEquals(1, result.size());
        verify(videoRepository).findByArtistId(testUserId);
    }
}