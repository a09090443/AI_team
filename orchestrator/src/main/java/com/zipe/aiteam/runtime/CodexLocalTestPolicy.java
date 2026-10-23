package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/**
 * 顯式配置的本機測試能力：隔離 namespace 內 TCP，不授權 Proxy 外連或主機服務。
 *
 * codex-cli 0.153.4 實測的參數契約不對稱，兩邊不能共用同一組參數：
 *   codex sandbox  -P/--permission-profile 是必填，指定要套用的具名 profile
 *   codex exec     完全不接受 -P（結束碼 2，unexpected argument），
 *                  改以 -c default_permissions="<名稱>" 指定套用哪一個 profile；
 *                  只給 [permissions] 而不給 default_permissions 會被拒絕。
 * 兩者共用 -c permissions.<名稱>={...} 的 profile 定義本身。
 */
final class CodexLocalTestPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PROFILE = "ai_team_local_tests";

    /** profile 定義本身；sandbox 與 exec 共用。 */
    private static List<String> profile(AgentRuntime.RuntimeRequest req, List<Path> statePaths) {
        Map<String,String> fs = new LinkedHashMap<>();
        fs.put(":root", "read"); fs.put(":slash_tmp", "write"); fs.put(":tmpdir", "write");
        Path cwd = req.workingDirectory().toAbsolutePath().normalize();
        // 延續原 workspace-write 的內層根目錄；細粒度寫入仍由外層 systemd 強制。
        // 子目錄若另成 inner writable root，Codex 的 metadata 掛載會阻擋 npm ci 清理。
        fs.put(cwd.toString(), "write");
        for (Path path : commandWrites(req, statePaths))
            if (!path.startsWith(cwd)) fs.put(path.toString(), "write");
        for (Path path : req.sandboxPolicy().readOnlyPins())
            if (Files.exists(path)) fs.put(path.toAbsolutePath().normalize().toString(), "read");
        for (String name : List.of(".git", ".agents", ".codex"))
            fs.put(req.workingDirectory().toAbsolutePath().normalize().resolve(name).toString(), "read");
        String entries = fs.entrySet().stream().map(e -> quote(e.getKey()) + "=" + quote(e.getValue()))
                .collect(java.util.stream.Collectors.joining(","));
        return List.of("-c",
                "permissions." + PROFILE + "={filesystem={" + entries + "},network={enabled=true,domains={},"
                        + "allow_local_binding=false,allow_upstream_proxy=false,unix_sockets={},"
                        + "dangerously_allow_all_unix_sockets=false,dangerously_allow_non_loopback_proxy=false}}",
                "-c", "features.network_proxy=true", "-c", "approval_policy=\"never\"");
    }

    /** codex sandbox 用：具名 profile 以 -P 指定。 */
    static List<String> sandboxArguments(AgentRuntime.RuntimeRequest req, List<Path> statePaths) {
        List<String> args = new ArrayList<>(List.of("-P", PROFILE));
        args.addAll(profile(req, statePaths));
        return List.copyOf(args);
    }

    /** codex exec 用：不得出現 -P，改以 default_permissions 指定同一個 profile。 */
    static List<String> execArguments(AgentRuntime.RuntimeRequest req, List<Path> statePaths) {
        List<String> args = new ArrayList<>(profile(req, statePaths));
        args.addAll(List.of("-c", "default_permissions=" + quote(PROFILE)));
        return List.copyOf(args);
    }

    static List<Path> commandWrites(AgentRuntime.RuntimeRequest req, List<Path> statePaths) {
        Path git = req.workingDirectory().toAbsolutePath().normalize().resolve(".git");
        return req.sandboxPolicy().writablePaths().stream().map(p -> p.toAbsolutePath().normalize())
                .filter(p -> !p.startsWith(git))
                .filter(p -> statePaths.stream().map(s -> s.toAbsolutePath().normalize()).noneMatch(p::startsWith)).toList();
    }
    private static String quote(String value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("無法編碼沙箱路徑", e); }
    }
    /**
     * 與 ConfiguredExecutor.LOOPBACK_NO_PROXY 相同：Codex network proxy 清空 NO_PROXY，Node 的 fetch 連 localhost 會被 proxy 拒絕；
     * 預檢與固定操作都在指令本身帶上這組環境變數。
     */
    static final List<String> LOOPBACK_NO_PROXY = List.of("env", "NO_PROXY=localhost,127.0.0.1,::1", "no_proxy=localhost,127.0.0.1,::1");
    /** TCP 往返之後再以 fetch 連 localhost 的 HTTP 服務：#1055 原始 TCP 可通，但走環境 proxy 的 HTTP 被擋，舊預檢抓不到。 */
    static final String TCP_PROBE = "const net=require('net'),http=require('http');let done=false;"
            + "const s=net.createServer(c=>c.end('ok'));"
            + "const fail=e=>{console.error(e);process.exit(24)};s.on('error',fail);"
            + "const httpProbe=()=>{const h=http.createServer((q,r)=>r.end('ok'));h.on('error',fail);"
            + "h.listen(0,'127.0.0.1',async()=>{try{const r=await fetch('http://localhost:'+h.address().port+'/');"
            + "if(await r.text()!=='ok')fail('bad http response')}catch(e){fail('local-http: '+((e.cause&&e.cause.message)||e.message))}"
            + "h.close(()=>console.log('local-http-ok'))})};"
            + "s.listen(0,'127.0.0.1',()=>{const c=net.connect(s.address().port,'127.0.0.1');"
            + "c.on('error',fail);c.on('data',d=>{if(d.toString()!=='ok')fail('bad response');done=true});"
            + "c.on('close',()=>s.close(()=>{if(!done)fail('missing response');console.log('local-tcp-ok');httpProbe()}))});"
            + "setTimeout(()=>fail('timeout'),5000).unref()";
}
