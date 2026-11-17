package de.moritzf.airplaycapture.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

import de.moritzf.airplaycapture.AppDirs;
import de.moritzf.airplaycapture.Settings;
import de.moritzf.airplaycapture.library.Library;
import de.moritzf.airplaycapture.library.LibraryTrack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TrackControllerTest {

    private static final String URL = "/api/tracks/test/file/m4b";
    private MockMvc mvc;

    @BeforeEach
    void setUp(@TempDir Path folder) throws IOException {
        Library library = new Library(new AppDirs(folder), new Settings());
        Files.writeString(library.libraryDir().resolve("Book.m4b"), "0123456789");
        LibraryTrack track = new LibraryTrack();
        track.id = "test";
        track.files.put("m4b", "Book.m4b");
        library.add(track);
        mvc = MockMvcBuilders.standaloneSetup(
            new TrackController(library, null, null, null, null)
        ).build();
    }

    @Test
    void supportsSuffixAndOpenEndedRanges() throws Exception {
        for (String header : new String[] { "bytes=-3", "bytes=7-" }) {
            var response = mvc.perform(get(URL).header("Range", header)).andReturn().getResponse();
            assertEquals(206, response.getStatus());
            assertEquals("bytes 7-9/10", response.getHeader("Content-Range"));
            assertEquals("789", response.getContentAsString());
        }
    }

    @Test
    void rejectsAnUnsatisfiableRange() throws Exception {
        var response = mvc.perform(get(URL).header("Range", "bytes=10-")).andReturn().getResponse();
        assertEquals(416, response.getStatus());
        assertEquals("bytes */10", response.getHeader("Content-Range"));
    }

    @Test
    void headAndDownloadKeepMediaHeaders() throws Exception {
        var response = mvc.perform(head(URL)).andReturn().getResponse();
        assertEquals("10", response.getHeader("Content-Length"));
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        response = mvc.perform(get(URL).param("download", "")).andReturn().getResponse();
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
        assertTrue(response.getHeader("Content-Disposition").contains("Book.m4b"));
    }
}
