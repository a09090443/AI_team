package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import static com.zipe.aiteam.flow.TeamDefinition.*;

@Component
public class TeamCatalog {
    private final Snapshot snapshot;
    private final Environment environment;
    public TeamCatalog(Environment environment) throws Exception {
        this.environment = environment;
        String file = environment.getProperty("AI_TEAM_DEFINITION", "");
        snapshot = file.isBlank() ? null : load(Path.of(file), environment::getProperty);
    }
    public boolean enabled() { return snapshot != null; }
    public Snapshot snapshot() { return Objects.requireNonNull(snapshot, "未啟用配置流程"); }
    public String secret(String name) { return name == null || name.isBlank() ? "" : environment.getProperty(name, "").trim(); }
    public Role role(String id) { return enabled() ? snapshot.definition().roles().get(id.toLowerCase(Locale.ROOT).replaceFirst("^direct_", "")) : null; }

    public static Snapshot load(Path file, Function<String,String> env) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        TeamDefinition definition = mapper.readValue(Files.readString(file), TeamDefinition.class);
        validate(definition);
        Path root = file.toAbsolutePath().getParent().toRealPath();
        Map<String,String> assets = new TreeMap<>();
        for (Role role : definition.roles().values()) readAsset(root, role.prompt(), assets);
        if (definition.sharedInstructions() != null) readAsset(root, definition.sharedInstructions(), assets);
        for (Node node : definition.nodes().values()) if (node.kind() == Kind.AGENT) {
            if (node.instructions() != null) readAsset(root, node.instructions(), assets);
            if (node.skills() != null) for (String skill : node.skills()) readAsset(root, skill, assets);
            readAsset(root, node.schema(), assets);
            JsonNode schema = mapper.readTree(assets.get(node.schema()));
            require(schema.isObject() && schema.path("properties").isObject(), "schema 必須是具 properties 的 object：" + node.schema());
        }
        // Freeze the shared contract in new v2 snapshots; existing persisted snapshots remain unchanged.
        if (definition.version() >= 2) assets.put(IssueTracker.CONTRACT, IssueTracker.INSTRUCTIONS);
        String repository = env.apply(definition.project().repositoryEnv());
        require(repository != null && !repository.isBlank(), "未設定專案 repositoryEnv");
        Path repo = Path.of(repository).toRealPath();
        require(Files.isDirectory(repo.resolve(".git")), "專案不是 Git repository");
        String frozen = mapper.writeValueAsString(definition) + "\n" + repo + "\n" + mapper.writeValueAsString(assets);
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frozen.getBytes(StandardCharsets.UTF_8)));
        return new Snapshot(revision, definition, repo.toString(), Map.copyOf(assets));
    }
    private static void readAsset(Path root, String name, Map<String,String> assets) throws Exception {
        require(name != null && !name.isBlank() && !Path.of(name).isAbsolute(), "資產須為配置目錄內的相對路徑");
        Path path = root.resolve(name).normalize().toRealPath();
        require(path.startsWith(root), "資產越出配置目錄：" + name);
        require(Files.size(path) <= 256_000, "配置資產過大：" + name);
        assets.put(name, Files.readString(path));
    }
    /**
     * 不擋配置、但建配置時就該知道的問題。
     *
     * 唯讀的分流（其他節點受阻時交接的目標）與審查節點產出的是「我查過了」的判斷；它的角色若沒有任何能執行的查核工具，
     * 只能讀檔案，一旦想看 git 狀態或建置結果就會工具遭拒，結論會被系統作廢。
     * 這在執行時才發現代價很高（#1014 的分流節點因此反覆被判受阻），所以在靜態驗證時先提醒。
     */
    public static List<String> warnings(TeamDefinition d) {
        List<String> warnings = new ArrayList<>();
        // 需要「核實」而不只是「閱讀」的，是其他節點受阻時交接過來的節點，以及審查節點。
        Set<String> blockedTargets = new HashSet<>();
        d.nodes().values().forEach(node -> { if (node.blocked() != null) blockedTargets.add(node.blocked()); });
        d.nodes().forEach((id, node) -> {
            if (node.kind() != Kind.AGENT || node.scope() != Scope.READ_ONLY) return;
            if (node.gate() != Gate.REVIEW && !blockedTargets.contains(id)) return;
            Role role = d.roles().get(node.role());
            if (role == null) return;
            boolean canInspect = role.tools() != null && role.tools().stream()
                    .anyMatch(tool -> tool != null && tool.startsWith("Bash"));
            if (!canInspect) warnings.add("節點 " + id + "（" + node.gate() + "）的角色 " + node.role()
                    + " 沒有任何可執行的查核工具，只能讀檔；若它需要核實版本控制或建置狀態，"
                    + "請補上唯讀權限（例如 Bash(git log *)、Bash(git show *)），否則工具遭拒時結論會被作廢。");
        });
        return List.copyOf(warnings);
    }

    public static void validate(TeamDefinition d) {
        require(d != null && (d.version() == 1 || d.version() == 2), "不支援的配置版本");
        require(d.id() != null && d.id().matches("[a-z][a-z0-9_-]*"), "無效的專案 ID");
        require(d.project() != null && d.project().name() != null && !d.project().name().isBlank(), "缺少專案名稱");
        require(d.project().repositoryEnv() != null && d.project().repositoryEnv().matches("[A-Z][A-Z0-9_]*"), "repositoryEnv 必須是環境變數名稱");
        require(d.project().baseBranch() != null && d.project().baseBranch().matches("[A-Za-z0-9][A-Za-z0-9/_-]*"), "須明確指定合法基準分支");
        require(d.roles() != null && !d.roles().isEmpty() && d.nodes() != null && !d.nodes().isEmpty(), "角色與節點不能為空");
        require(d.maxTransitions() >= 1 && d.maxTransitions() <= 64, "流程上限必須為 1..64");
        require(d.nodes().containsKey(d.entry()), "入口不存在");
        Set<String> tokenRefs = new HashSet<>();
        d.roles().forEach((id, r) -> {
            require(id.matches("[a-z][a-z0-9_]*") && !id.equals("system"), "無效角色 ID：" + id);
            require(r != null && r.name() != null && !r.name().isBlank() && r.runtime() != null && !r.runtime().isBlank(), "角色缺名稱或 runtime：" + id);
            require(r.tools() != null && !r.tools().isEmpty(), "角色缺工具政策：" + id);
            if (r.reasoningEffort() != null && !r.reasoningEffort().isBlank()) {
                require("codex".equals(r.runtime()), "reasoningEffort 目前僅支援 codex：" + id);
                require(Set.of("low", "medium", "high", "xhigh").contains(r.reasoningEffort()), "不支援的 reasoningEffort：" + id);
            }
            for (String ref : List.of(r.botTokenEnv() == null ? "" : r.botTokenEnv(), r.appTokenEnv() == null ? "" : r.appTokenEnv())) {
                if (ref.isBlank()) continue;
                require(ref.matches("[A-Z][A-Z0-9_]*") && tokenRefs.add(ref), "token 只允許互不重複的環境變數引用");
            }
        });
        d.nodes().forEach((id, n) -> {
            require(id.matches("[a-z][a-z0-9_]*") && !Set.of("human", "end").contains(id), "無效節點 ID：" + id);
            if (n != null) ProtectedPathFallback.validate(d, id, n);
            require(n != null && n.kind() != null, "節點缺 kind：" + id);
            if (n.kind() == Kind.AGENT) {
                if (n.skills() != null) {
                    require(n.skills().size() <= 8 && new HashSet<>(n.skills()).size() == n.skills().size(), "skills 最多八份且不得重複：" + id);
                    for (String skill : n.skills()) require(skill != null && skill.matches("skills/[a-z0-9-]+/(SKILL\\.md|references/[a-z0-9-]+\\.md)"), "無效 skill 路徑：" + id);
                }
                require(d.roles().containsKey(n.role()), "節點引用不存在角色：" + id);
                require(n.scope() != null && n.gate() != null, "節點缺權限或驗證政策：" + id);
                require(!n.localTestNetwork() || (n.scope() == Scope.LIMITED && n.gate() == Gate.VERIFICATION
                        && "codex".equals(d.roles().get(n.role()).runtime()) && d.project().requiredTools().contains("node")),
                        "本機測試網路只支援 Codex LIMITED 驗收，並須宣告 node 工具：" + id);
                // 節點逾時上限 3600 秒：#1053 實作含安裝、建置與前端測試，30 分鐘不夠；獨立驗證仍維持 1800 秒。
                require(n.maxVisits() >= 1 && n.maxVisits() <= 3 && n.timeoutSeconds() >= 1 && n.timeoutSeconds() <= 3600 && n.maxTurns() >= 1 && n.maxTurns() <= 80, "節點資源上限無效：" + id);
                require(n.scope() == Scope.READ_ONLY || n.workspace(), "寫入只能在獨立工作區：" + id);
                require(n.scope() != Scope.WORKSPACE || n.gate() == Gate.IMPLEMENTATION, "產品寫入必須通過實作品質驗證：" + id);
                require(n.gate() != Gate.REVIEW || (n.scope() == Scope.READ_ONLY && n.workspace()), "審查必須唯讀工作區：" + id);
                require(n.gate() != Gate.REQUIREMENTS || n.scope() == Scope.READ_ONLY, "需求基準節點必須唯讀");
                if (n.requires() != null) for (String dependency : n.requires())
                    require(!id.equals(dependency) && d.nodes().containsKey(dependency)
                            && d.nodes().get(dependency).kind() == Kind.AGENT, "無效的前置契約：" + id);
                if (n.scope() == Scope.LIMITED) {
                    require(n.writablePaths() != null && !n.writablePaths().isEmpty(), "限寫節點缺路徑：" + id);
                    n.writablePaths().forEach(PathRules::validate);
                    require(n.writablePaths().stream().allMatch(p -> p.endsWith("/**") && !p.equals("**/**")), "限寫政策須是明確的目錄 /**");
                }
                if (n.gate() == Gate.VERIFICATION) {
                    require(d.nodes().containsKey(n.plan()) && d.nodes().get(n.plan()).gate() == Gate.TEST_PLAN, "驗收缺少測試計畫節點：" + id);
                    require(n.workspace(), "驗收必須查看工作區：" + id);
                }
                checkTarget(d, n.next()); checkTarget(d, n.blocked());
                if (n.routes() != null) n.routes().values().forEach(t -> checkTarget(d, t));
            }
            if (n.kind() == Kind.APPROVAL) {
                require(n.requires() != null && !n.requires().isEmpty(), "核准節點缺驗收條件");
                Set<Gate> gates = new HashSet<>();
                for (String required : n.requires()) {
                    Node prerequisite = d.nodes().get(required);
                    require(prerequisite != null && prerequisite.kind() == Kind.AGENT, "核准條件引用不存在的執行節點");
                    gates.add(prerequisite.gate());
                }
                require(gates.containsAll(Set.of(Gate.IMPLEMENTATION, Gate.TEST_PLAN, Gate.VERIFICATION, Gate.REVIEW)), "發布不可省略實作、測試計畫、驗收與獨立審查");
                if (d.version() >= 2) require(gates.contains(Gate.REQUIREMENTS), "發布不可省略需求基準");
                Set<String> writers = new HashSet<>();
                n.requires().stream().map(d.nodes()::get).filter(p -> p.gate() == Gate.IMPLEMENTATION).forEach(p -> writers.add(p.role()));
                require(n.requires().stream().map(d.nodes()::get).filter(p -> p.gate() == Gate.REVIEW).noneMatch(p -> writers.contains(p.role())), "獨立審查不可由實作角色自行通過");
            }
        });
        require(d.project().protectedPaths() != null && !d.project().protectedPaths().isEmpty(), "須宣告產品保護路徑");
        require(d.project().requiredTools() != null && !d.project().requiredTools().isEmpty()
                && d.project().requiredTools().stream().allMatch(t -> t.matches("[a-zA-Z][a-zA-Z0-9_.+-]*")), "須宣告安全的 requiredTools 名稱");
        d.project().protectedPaths().forEach(PathRules::validate);
        if (d.project().cachePaths() != null) d.project().cachePaths().forEach(ConfiguredExecutor::validateCache);
        OperationContract.validate(d);
        ServiceDelivery.validate(d);
        AgentDispatch.validate(d);
        Consultation.validate(d);
        for (String id : d.nodes().keySet()) dependencies(d, id, new HashSet<>());
        if (d.version() >= 2) {
            require(d.nodes().values().stream().filter(n -> n.gate() == Gate.REQUIREMENTS).count() == 1,
                    "v2 必須有唯一需求基準節點");
            require(d.nodes().get(d.entry()).gate() == Gate.REQUIREMENTS, "v2 入口必須建立需求基準");
            for (var entry : d.nodes().entrySet()) {
                Node node = entry.getValue();
                Set<String> dependencies = dependencies(d, entry.getKey(), new HashSet<>());
                Set<Gate> gates = new HashSet<>(); dependencies.forEach(id -> gates.add(d.nodes().get(id).gate()));
                if (node.gate() == Gate.TEST_PLAN) require(gates.contains(Gate.REQUIREMENTS), "測試計畫必須相依於需求基準");
                if (node.gate() == Gate.IMPLEMENTATION) require(gates.contains(Gate.TEST_PLAN), "實作必須相依於測試計畫");
                if (node.gate() == Gate.VERIFICATION) require(gates.contains(Gate.IMPLEMENTATION) && dependencies.contains(node.plan()), "驗收必須相依於實作與指定測試計畫");
                if (node.gate() == Gate.REVIEW) require(gates.contains(Gate.VERIFICATION), "審查必須相依於驗收");
                if (node.kind() == Kind.APPROVAL) require(new HashSet<>(node.requires()).containsAll(dependencies), "核准清單必須包含所有間接前置契約");
            }
            var verification = d.project().verification();
            require(verification != null && verification.commands() != null && !verification.commands().isEmpty()
                    && verification.commands().size() <= 5 && verification.timeoutSeconds() >= 1
                    && verification.timeoutSeconds() <= 1800, "v2 缺少有界的獨立驗證指令");
            for (var command : verification.commands()) {
                require(command != null && !command.isEmpty() && command.size() <= 32
                        && command.stream().allMatch(a -> a != null && !a.isBlank() && a.length() <= 1000 && a.indexOf('\0') < 0), "無效驗證 argv");
                require(d.project().requiredTools().contains(command.getFirst())
                        && !Set.of("sh", "bash", "zsh", "cmd", "powershell", "pwsh").contains(command.getFirst()),
                        "驗證執行檔須來自 requiredTools，不能使用 shell");
            }
            if (verification.docOnlyPaths() != null) {
                require(!verification.docOnlyPaths().isEmpty() && verification.docOnlyPaths().size() <= 20,
                        "verification.docOnlyPaths 若有設定須為 1～20 條");
                verification.docOnlyPaths().forEach(DocOnlyChanges::validate);
            }
            VerificationPlan.validate(verification);
        }
        var autonomy = d.project().autonomy();
        if (autonomy != null)
            require(autonomy.modelTimeoutRecoveries() >= 0 && autonomy.modelTimeoutRecoveries() <= 3
                    && autonomy.maxTimeoutSeconds() >= 0 && autonomy.maxTimeoutSeconds() <= 3600
                    && autonomy.verificationContinuations() >= 0 && autonomy.verificationContinuations() <= 3,
                    "project.autonomy 上限無效：模型逾時接續與驗證續驗各 0～3 次，maxTimeoutSeconds 0～3600");
        if (autonomy != null) {
            require(autonomy.pullRequestSyncRounds() >= 0 && autonomy.pullRequestSyncRounds() <= 3,
                    "project.autonomy.pullRequestSyncRounds 須為 0～3");
            if (autonomy.pullRequestSyncRounds() > 0)
                require(PullRequestSync.implementationNode(d) != null,
                        "project.autonomy.pullRequestSyncRounds 需要主線（entry 沿 next）上有 gate=IMPLEMENTATION 的節點來解衝突");
        }
        Set<String> reached = new HashSet<>(); visit(d, d.entry(), reached);
        require(reached.size() == d.nodes().size(), "存在不可到達節點，請修正流程");
    }
    private static void visit(TeamDefinition d, String id, Set<String> seen) {
        if (Set.of("human", "end").contains(id) || !seen.add(id)) return;
        Node n = d.nodes().get(id);
        if (n.kind() != Kind.AGENT) return;
        visit(d, n.next(), seen); visit(d, n.blocked(), seen);
        if (n.routes() != null) n.routes().values().forEach(t -> visit(d, t, seen));
    }
    private static void checkTarget(TeamDefinition d, String target) {
        require(target != null && (Set.of("human", "end").contains(target) || d.nodes().containsKey(target)), "引用不存在的流程目標：" + target);
    }
    private static Set<String> dependencies(TeamDefinition d, String id, Set<String> visiting) {
        require(visiting.add(id), "前置契約形成循環：" + id);
        Set<String> result = new HashSet<>();
        List<String> required = d.nodes().get(id).requires();
        if (required != null) for (String dependency : required) {
            result.add(dependency); result.addAll(dependencies(d, dependency, visiting));
        }
        visiting.remove(id); return result;
    }
    static void require(boolean condition, String detail) { if (!condition) throw new IllegalArgumentException(detail); }
}
