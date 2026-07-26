package com.vca.web.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicUploadRouteTest {

    @Test
    void acceptsId3AndMpegFrameHeaders() {
        assertTrue(MusicUploadRoute.looksLikeMp3(new byte[]{'I', 'D', '3', 4}));
        assertTrue(MusicUploadRoute.looksLikeMp3(new byte[]{0, 0, (byte) 0xff, (byte) 0xfb, 0}));
    }

    @Test
    void rejectsRenamedNonMp3Content() {
        assertFalse(MusicUploadRoute.looksLikeMp3("not audio".getBytes()));
        assertFalse(MusicUploadRoute.looksLikeMp3(new byte[0]));
    }
}
