package com.memorylane.backend.photo;

import com.memorylane.backend.auth.SessionAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the photo endpoints (status, error codes, headers), checked without a database. */
@WebMvcTest(PhotoController.class)
class PhotoHttpContractTest {
    // The session filter is not part of a web slice; set the user the way it would.
    private static final String SESSION_USER = SessionAuthenticationFilter.class.getName() + ".userId";

    @Autowired MockMvc mvc;
    @MockitoBean PhotoService photos;

    private final UUID user = UUID.randomUUID();
    private final UUID memory = UUID.randomUUID();

    @Test
    void photoRuleErrorsAreNotSwallowedByTheSharedCatchAll() throws Exception {
        when(photos.upload(any(), any(), any(), any(), any())).thenThrow(PhotoRejected.limitReached());

        mvc.perform(multipart("/api/v1/memories/{memory}/photos", memory)
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", PhotoFileTest.JPEG))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .requestAttr(SESSION_USER, user))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_LIMIT_REACHED"));
    }

    @Test
    void photoContentIsServedWithItsCheckedTypeAndCannotRunInABrowser() throws Exception {
        UUID photo = UUID.randomUUID();
        when(photos.content(user, memory, photo)).thenReturn(new PhotoService.Content(
                "image/png", PhotoFileTest.PNG.length, new ByteArrayInputStream(PhotoFileTest.PNG)));

        mvc.perform(get("/api/v1/memories/{memory}/photos/{photo}/content", memory, photo)
                        .requestAttr(SESSION_USER, user))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(PhotoFileTest.PNG));
    }

    @Test
    void malformedIdInThePathIsInvalidInput() throws Exception {
        mvc.perform(get("/api/v1/memories/not-a-uuid/photos").requestAttr(SESSION_USER, user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
