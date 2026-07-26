package com.vca.web.music;

import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicCatalogRouteTest {

    private final MusicProvider provider = new MusicProvider() {
        @Override
        public Mono<MusicTrack> search(String query) {
            return Mono.empty();
        }

        @Override
        public Mono<List<MusicTrack>> catalog() {
            return Mono.just(List.of(
                    new MusicTrack("晴天", "周杰伦", "https://oss/晴天.mp3", null, "晴天.lrc", 0, true),
                    new MusicTrack("七里香", "周杰伦", "https://oss/七里香.mp3", null, null, 0, true)));
        }

        @Override
        public Mono<String> lyrics(String lyricsId) {
            return "晴天.lrc".equals(lyricsId)
                    ? Mono.just("[00:01.00]故事的小黄花")
                    : Mono.empty();
        }
    };

    @Test
    void returnsCompleteCatalogForAuthenticatedUser() {
        WebTestClient client = WebTestClient.bindToRouterFunction(
                MusicCatalogRoute.create(provider, token -> "valid".equals(token) ? "1" : null, ""))
                .build();

        client.get().uri("/api/music/catalog")
                .header("Authorization", "Bearer valid")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(result -> {
                    String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
                    assertTrue(body.contains("\"title\":\"晴天\""));
                    assertTrue(body.contains("\"title\":\"七里香\""));
                    assertTrue(body.contains("\"url\":\"https://oss/七里香.mp3\""));
                    assertTrue(body.contains("\"lyricsId\":\"晴天.lrc\""));
                });
    }

    @Test
    void returnsMatchedLyricsForAuthenticatedUser() {
        WebTestClient client = WebTestClient.bindToRouterFunction(
                MusicCatalogRoute.create(provider, token -> "valid".equals(token) ? "1" : null, ""))
                .build();

        client.get().uri(uri -> uri.path("/api/music/lyrics")
                        .queryParam("id", "晴天.lrc").build())
                .header("Authorization", "Bearer valid")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/plain")
                .expectBody(String.class).isEqualTo("[00:01.00]故事的小黄花");
    }

    @Test
    void rejectsInvalidToken() {
        WebTestClient client = WebTestClient.bindToRouterFunction(
                MusicCatalogRoute.create(provider, token -> null, ""))
                .build();

        client.get().uri("/api/music/catalog")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
