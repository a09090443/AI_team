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
        for (Node node : definition.nodes().values()) if (node.kind() == Kind.AGENT) {
            if (node.instructions() != null) readAsset(root, node.instructions(), assets);
            readAsset(root, node.schema(), assets);
            JsonNode schema = mapper.readTree(assets.get(node.schema()));
            require(schema.isObject() && schema.path("properties").isObject(), "schema 必須是具 properties 的 object：" + node.schema());
        }
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
            for (String ref : List.of(r.botTokenEnv() == null ? "" : r.botTokenEnv(), r.appTokenEnv() == null ? "" : r.appTokenEnv())) {
                if (ref.isBlank()) continue;
                require(ref.matches("[A-Z][A-Z0-9_]*") && tokenRefs.add(ref), "token 只允許互不重複的環境變數引用");
            }
        });
        d.nodes().forEach((id, n) -> {
            require(id.matches("[a-z][a-z0-9_]*") && !Set.of("human", "end").contains(id), "無效節點 ID：" + id);
            require(n != null && n.kind() != null, "節點缺 kind：" + id);
            if (n.kind() == Kind.AGENT) {
                require(d.roles().containsKey(n.role()), "節點引用不存在角色：" + id);
                require(n.scope() != null && n.gate() != null, "節點缺權限或驗證政策：" + id);
                require(n.maxVisits() >= 1 && n.maxVisits() <= 3 && n.timeoutSeconds() >= 1 && n.timeoutSeconds() <= 1800 && n.maxTurns() >= 1 && n.maxTurns() <= 80, "節點資源上限無效：" + id);
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
