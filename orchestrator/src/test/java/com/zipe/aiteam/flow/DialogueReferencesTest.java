package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DialogueReferencesTest {
    HttpServer server;
    String base;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/getControllers", exchange -> reply(exchange, 200, "{\"data\":[{\"publishUri\":\"inventory\"}]}"));
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://elsewhere.invalid/secret");
            reply(exchange, 302, "");
        });
        server.createContext("/big", exchange -> reply(exchange, 200, "x".repeat(DialogueReferences.MAX_BODY + 100)));
        server.createContext("/broken", exchange -> reply(exchange, 500, "boom"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (var out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    @Test void systemFetchesConfiguredUrlsAndLabelsTheEvidence() {
        String text = DialogueReferences.collect(List.of(new TeamDefinition.Reference("RESTful 清單", base + "/api/getControllers"),
                new TeamDefinition.Reference("壞掉", base + "/broken")));
        assertThat(text).contains("系統查得的服務現況", "RESTful 清單：GET " + base + "/api/getControllers", "HTTP 200",
                "\"publishUri\":\"inventory\"", "HTTP 500", "boom", "台北時間", "不得以推測補上");
    }
    @Test void redirectsAreNotFollowedAndLargeBodiesAreTruncated() {
        String text = DialogueReferences.collect(List.of(new TeamDefinition.Reference("轉址", base + "/moved"),
                new TeamDefinition.Reference("大", base + "/big")));
        assertThat(text).contains("HTTP 302", "系統不跟隨", "已截斷");
        assertThat(text.length()).isLessThan(DialogueReferences.MAX_BODY + 2000);
    }
    @Test void unreachableUrlIsReportedAsUnknownInsteadOfFailingTheDialogue() {
        server.stop(0);
        assertThat(DialogueReferences.collect(List.of(new TeamDefinition.Reference("離線", base + "/api/getControllers"))))
                .contains("查詢失敗，現況未知");
    }
    @Test void noReferencesAddNothing() {
        assertThat(DialogueReferences.collect(null)).isEmpty();
        assertThat(DialogueReferences.collect(List.of())).isEmpty();
    }

    final ObjectMapper mapper = new ObjectMapper();
    void validateWith(String... pairs) throws Exception {
        var config = (ObjectNode) mapper.readTree(Files.readString(Path.of("teams/node-web.json")));
        var references = ((ObjectNode) config.path("roles").path("frontend")).putArray("references");
        for (int i = 0; i < pairs.length; i += 2) references.addObject().put("label", pairs[i]).put("url", pairs[i + 1]);
        TeamCatalog.validate(mapper.treeToValue(config, TeamDefinition.class));
    }
    @Test void catalogAcceptsPlainHttpReferencesAndRejectsUnsafeOnes() throws Exception {
        validateWith("OpenAPI", "http://127.0.0.1:8080/v3/api-docs", "清單", "https://example.test/api/list");
        assertThatThrownBy(() -> validateWith("帳密", "http://user:pw@example.test/a")).hasMessageContaining("不含帳密");
        assertThatThrownBy(() -> validateWith("檔案", "file:///etc/passwd")).hasMessageContaining("http/https");
        assertThatThrownBy(() -> validateWith("重複", "http://a.test/", "重複", "http://b.test/")).hasMessageContaining("不得重複");
        assertThatThrownBy(() -> validateWith("1", "http://a.test/1", "2", "http://a.test/2", "3", "http://a.test/3",
                "4", "http://a.test/4", "5", "http://a.test/5")).hasMessageContaining("最多 4 筆");
    }
}
