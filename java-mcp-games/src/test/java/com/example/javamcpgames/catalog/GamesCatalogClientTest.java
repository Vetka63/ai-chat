package com.example.javamcpgames.catalog;

import org.springframework.ai.mcp.annotation.McpTool;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Контракт Java-адаптера к общему mock API без запуска Docker. */
class GamesCatalogClientTest {

    @Test
    void toolDescriptionAllowsUnknownGameLookup() throws NoSuchMethodException {
        McpTool tool = GamesTools.class.getMethod("searchGames", String.class).getAnnotation(McpTool.class);
        assertTrue(tool.description().contains("нет надёжных сведений"));
        assertTrue(tool.description().contains("не требуй слова «каталог»"));
        assertTrue(tool.description().contains("Не используй для общих вопросов"));
    }

    @Test
    void searchesMockApiAndMapsSameResultAsPythonServer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://games.test/games?query=%D0%BA%D0%BE%D1%81%D0%BC%D0%BE%D1%81"))
                .andRespond(withSuccess("""
                    {"query":"космос","games":[{"title":"Звёздные тропы","description":"Экспедиция в космос."}]}
                    """, MediaType.APPLICATION_JSON));

        GamesResult result = new GamesCatalogClient(builder, "http://games.test").search(" космос ");

        assertEquals("космос", result.query());
        assertEquals("Звёздные тропы", result.games().getFirst().title());
        server.verify();
    }

    @Test
    void rejectsEmptyQueryBeforeCallingApi() {
        GamesCatalogClient client = new GamesCatalogClient(RestClient.builder(), "http://games.test");
        assertThrows(IllegalArgumentException.class, () -> client.search("   "));
        assertThrows(IllegalArgumentException.class, () -> client.search("x".repeat(101)));
    }

    @Test
    void mapsUnknownGameToEmptyList() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://games.test/games?query=unknown"))
                .andRespond(withSuccess("{\"query\":\"unknown\",\"games\":[]}", MediaType.APPLICATION_JSON));

        GamesResult result = new GamesCatalogClient(builder, "http://games.test").search("unknown");

        assertEquals(0, result.games().size());
        server.verify();
    }
}
