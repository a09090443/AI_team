package com.zipe.aiteam.flow;

import com.zipe.aiteam.runtime.Diagnostics;
import com.zipe.aiteam.task.TaipeiTime;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色對話前，系統代查角色配置的固定參考網址（服務清單、OpenAPI 等），把結果附進脈絡。
 * 對話模型不能上網、agent 沙箱也可能連不到服務網段，但服務程序所在的主機連得到；
 * 網址由管理者寫在配置，模型不能提供或改寫，所以只查這些、只用 GET、不跟隨 redirect、不帶憑證。
 */
public final class DialogueReferences {
    private DialogueReferences() {}
    static final int MAX_REFERENCES = 4;
    static final int MAX_BODY = 32 * 1024;
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    static void validate(String role, List<TeamDefinition.Reference> references) {
        TeamCatalog.require(references.size() <= MAX_REFERENCES, "references 最多 " + MAX_REFERENCES + " 筆：" + role);
        Set<String> labels = new HashSet<>();
        for (TeamDefinition.Reference reference : references) {
            TeamCatalog.require(reference != null && reference.label() != null && reference.label().matches("[^\\n]{1,80}")
                    && labels.add(reference.label()), "references 的 label 必填、單行且不得重複：" + role);
            URI uri;
            try { uri = reference.url() == null || reference.url().length() > 2048 ? null : new URI(reference.url()); }
            catch (Exception e) { uri = null; }
            TeamCatalog.require(uri != null && uri.getHost() != null && uri.getUserInfo() == null
                    && Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(java.util.Locale.ROOT)),
                    "references 的 url 必須是不含帳密的 http/https 網址：" + role);
        }
    }

    /** 逐筆 GET，回傳要附進對話脈絡的固定格式文字；沒有設定時回空字串。 */
    public static String collect(List<TeamDefinition.Reference> references) {
        if (references == null || references.isEmpty()) return "";
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
        StringBuilder text = new StringBuilder("\n【系統查得的服務現況：服務主機以固定網址實測，非模型推測】");
        for (TeamDefinition.Reference reference : references) {
            text.append("\n- ").append(reference.label()).append("：GET ").append(reference.url())
                    .append("（查詢時間 ").append(TaipeiTime.seconds(Instant.now())).append("）\n");
            try {
                HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(reference.url()))
                        .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] body;
                boolean truncated;
                try (InputStream in = response.body()) {
                    body = in.readNBytes(MAX_BODY + 1);
                    truncated = body.length > MAX_BODY;
                }
                String content = new String(body, 0, Math.min(body.length, MAX_BODY), StandardCharsets.UTF_8);
                text.append("  HTTP ").append(response.statusCode());
                if (response.statusCode() / 100 == 3)
                    text.append("（轉址到 ").append(response.headers().firstValue("location").orElse("未提供")).append("，系統不跟隨）");
                text.append(truncated ? "，內容超過 " + MAX_BODY / 1024 + " KB 已截斷" : "").append("\n  ")
                        .append(Diagnostics.safe(content).replace("\n", "\n  "));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text.append("  查詢中斷，現況未知");
                break;
            } catch (Exception e) {
                text.append("  查詢失敗，現況未知：").append(Diagnostics.safe(e.getClass().getSimpleName() + " " + e.getMessage()));
            }
        }
        return text.append("\n以上是本次查得的內容；查詢失敗或沒有列出的項目代表現況未知或未部署，不得以推測補上。\n").toString();
    }
}
