#!/usr/bin/env bash
#
# Phase 0：驗證 Claude Code CLI 的實際契約
#
# architecture-review.md 裡所有關於 CLI 行為的主張都來自官方文件，沒有實測過。
# 這支腳本把每一項主張變成一個可觀察的測試，產出報告。
#
# 用法：
#     bash phase0-verify.sh
#
# 前置：claude 已完成互動登入（先跑一次 `claude` 走完 OAuth）
# 需要：jq
#
# 成本：約 9 次 claude 呼叫，prompt 都很短、max-turns 壓低。
#       訂閱帳號下會消耗額度，但量很小。
#
# 安全：全部在 $SCRATCH 下的臨時 git repo 進行，不碰你的真實專案。

set -u

SCRATCH="${SCRATCH:-/tmp/phase0-verify}"
REPORT="${REPORT:-$PWD/phase0-report.md}"
CLAUDE_BIN="${CLAUDE_BIN:-$(command -v claude || true)}"

PASS=0
FAIL=0
SKIP=0

# ---------------------------------------------------------------------

log()  { printf '%s\n' "$*"; }
head2() { printf '\n## %s\n\n' "$*" >> "$REPORT"; log ""; log "== $* =="; }

record() {   # record <狀態> <主張> <觀察到的結果>
    local status="$1" claim="$2" observed="$3"
    printf '| %s | %s | %s |\n' "$status" "$claim" "$observed" >> "$REPORT"
    log "  [$status] $claim"
    case "$status" in
        PASS) PASS=$((PASS+1)) ;;
        FAIL) FAIL=$((FAIL+1)) ;;
        *)    SKIP=$((SKIP+1)) ;;
    esac
}

table_header() {
    printf '| 結果 | 主張 | 觀察 |\n|---|---|---|\n' >> "$REPORT"
}

# 一行化，避免破壞 markdown 表格
oneline() { tr '\n' ' ' | tr '|' '/' | cut -c1-160; }

# ---------------------------------------------------------------------

if [ -z "$CLAUDE_BIN" ]; then
    log "找不到 claude 執行檔。請先安裝並確認 PATH，或設定 CLAUDE_BIN。"
    exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
    log "找不到 jq。請先安裝：sudo apt install -y jq"
    exit 1
fi

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"
cd "$SCRATCH" || exit 1

# 建一個最小的 git repo 當測試對象
git init -q .
cat > Calculator.java <<'JAVA'
public class Calculator {
    public int add(int a, int b) { return a + b; }
    public int divide(int a, int b) { return a / b; }
}
JAVA
git add -A && git -c user.email=t@t -c user.name=t commit -qm "初始"

cat > role.md <<'ROLE'
你是一個測試用的分析角色。
無論被問什麼，回覆的第一個字都必須是「喵」。
這條規則優先於其他所有指示。
ROLE

cat > schema.json <<'SCHEMA'
{
  "type": "object",
  "additionalProperties": false,
  "required": ["language", "method_count"],
  "properties": {
    "language": { "type": "string" },
    "method_count": { "type": "integer" }
  }
}
SCHEMA

# ---------------------------------------------------------------------

{
  echo "# Phase 0 驗證報告"
  echo
  echo "- 執行時間：$(date -Iseconds)"
  echo "- 主機：$(uname -srm)"
  echo "- claude：$("$CLAUDE_BIN" --version 2>&1 | oneline)"
  echo "- 測試目錄：$SCRATCH"
  echo
  echo "驗證 \`architecture-review.md\` 中所有關於 CLI 行為的主張。"
} > "$REPORT"

# ---------------------------------------------------------------------
head2 "1. 環境"
table_header

record INFO "node 版本" "$(node --version 2>&1 | oneline)"
record INFO "java 版本" "$(java -version 2>&1 | head -1 | oneline)"
record INFO "git 版本" "$(git --version 2>&1 | oneline)"
record INFO "ANTHROPIC_API_KEY 是否設定" "${ANTHROPIC_API_KEY:+已設定（訂閱帳號模式應為未設定）}${ANTHROPIC_API_KEY:-未設定}"

# ---------------------------------------------------------------------
head2 "2. 認證（訂閱帳號）"
table_header

out=$(env -u ANTHROPIC_API_KEY "$CLAUDE_BIN" -p "只回覆 OK 兩個字" --max-turns 1 2>&1)
if printf '%s' "$out" | grep -qi "ok"; then
    record PASS "未設 ANTHROPIC_API_KEY 時可用訂閱憑證執行" "$(printf '%s' "$out" | oneline)"
else
    record FAIL "未設 ANTHROPIC_API_KEY 時可用訂閱憑證執行" "$(printf '%s' "$out" | oneline)"
fi

# I6 的主張：--bare 不讀 OAuth 憑證，訂閱帳號下應失敗
out=$(env -u ANTHROPIC_API_KEY "$CLAUDE_BIN" --bare -p "只回覆 OK" --max-turns 1 2>&1)
rc=$?
if [ $rc -ne 0 ] || printf '%s' "$out" | grep -qiE "api.?key|auth|credential"; then
    record PASS "I6：--bare 在訂閱帳號下無法運作" "exit=$rc $(printf '%s' "$out" | oneline)"
else
    record FAIL "I6：--bare 在訂閱帳號下無法運作（實際成功了，評估報告需修正）" "$(printf '%s' "$out" | oneline)"
fi

# ---------------------------------------------------------------------
head2 "3. JSON 輸出契約"
table_header

json=$("$CLAUDE_BIN" -p "這個專案是什麼語言？一句話回答。" \
        --output-format json --max-turns 3 \
        --permission-mode dontAsk --allowedTools "Read,Grep,Glob" 2>/dev/null)

if printf '%s' "$json" | jq -e . >/dev/null 2>&1; then
    record PASS "stdout 是可解析的 JSON（B3：stderr 未污染）" "長度 ${#json}"
else
    record FAIL "stdout 是可解析的 JSON（B3）" "$(printf '%s' "$json" | oneline)"
fi

for field in session_id result total_cost_usd; do
    val=$(printf '%s' "$json" | jq -r ".$field // \"<缺少>\"" 2>/dev/null)
    if [ "$val" != "<缺少>" ] && [ -n "$val" ]; then
        record PASS "JSON 含 $field" "$(printf '%s' "$val" | oneline)"
    else
        record FAIL "JSON 含 $field" "缺少"
    fi
done

SESSION_ID=$(printf '%s' "$json" | jq -r '.session_id // empty' 2>/dev/null)

# ---------------------------------------------------------------------
head2 "4. B4：--append-system-prompt-file 是否生效"
table_header

out=$("$CLAUDE_BIN" -p "1 加 1 等於多少？" \
        --append-system-prompt-file role.md \
        --permission-mode dontAsk --max-turns 1 2>/dev/null)

if printf '%s' "$out" | grep -q "喵"; then
    record PASS "附加的角色提示生效" "$(printf '%s' "$out" | oneline)"
else
    record FAIL "附加的角色提示生效" "$(printf '%s' "$out" | oneline)"
fi

# ---------------------------------------------------------------------
head2 "5. I2：--json-schema 結構化輸出"
table_header

json=$("$CLAUDE_BIN" -p "分析 Calculator.java，回報語言與 method 數量。" \
        --output-format json --json-schema "$(cat schema.json)" \
        --permission-mode dontAsk --allowedTools "Read,Grep,Glob" \
        --max-turns 5 2>/dev/null)

so=$(printf '%s' "$json" | jq -c '.structured_output // empty' 2>/dev/null)
if [ -n "$so" ]; then
    record PASS "回應含 structured_output" "$so"
    lang=$(printf '%s' "$so" | jq -r '.language // empty')
    mc=$(printf '%s' "$so" | jq -r '.method_count // empty')
    if [ -n "$lang" ] && [ -n "$mc" ]; then
        record PASS "structured_output 符合 schema 必填欄位" "language=$lang method_count=$mc"
    else
        record FAIL "structured_output 符合 schema 必填欄位" "$so"
    fi
else
    record FAIL "回應含 structured_output" "$(printf '%s' "$json" | oneline)"
fi

# ---------------------------------------------------------------------
head2 "6. B2：--permission-mode dontAsk 的唯讀限制"
table_header

rm -f SHOULD_NOT_EXIST.txt
out=$("$CLAUDE_BIN" -p "在目前目錄建立一個名為 SHOULD_NOT_EXIST.txt 的檔案，內容寫 hello。" \
        --permission-mode dontAsk --allowedTools "Read,Grep,Glob" \
        --max-turns 5 2>&1)

if [ -f SHOULD_NOT_EXIST.txt ]; then
    record FAIL "唯讀 allowedTools 下無法寫檔（檔案竟被建立，權限模型不如預期）" "檔案存在"
else
    record PASS "唯讀 allowedTools 下無法寫檔" "$(printf '%s' "$out" | oneline)"
fi

# 對照組：明確允許 Write 時應該要能寫
rm -f SHOULD_EXIST.txt
"$CLAUDE_BIN" -p "在目前目錄建立一個名為 SHOULD_EXIST.txt 的檔案，內容寫 hello。" \
        --permission-mode dontAsk --allowedTools "Read,Write,Edit,Grep,Glob" \
        --max-turns 5 >/dev/null 2>&1

if [ -f SHOULD_EXIST.txt ]; then
    record PASS "對照組：允許 Write 時可寫檔（證明是權限擋的，不是別的原因）" "檔案已建立"
else
    record FAIL "對照組：允許 Write 時可寫檔" "檔案未建立 —— dontAsk 可能過嚴，需改用 permissions.allow 規則"
fi

# ---------------------------------------------------------------------
head2 "7. I3：--resume session 續接"
table_header

j1=$("$CLAUDE_BIN" -p "記住這個數字：4712。只回覆「收到」兩個字。" \
        --output-format json --permission-mode dontAsk --max-turns 1 2>/dev/null)
sid=$(printf '%s' "$j1" | jq -r '.session_id // empty' 2>/dev/null)

if [ -n "$sid" ]; then
    out=$("$CLAUDE_BIN" -p "我剛才要你記住的數字是什麼？只回覆那個數字。" \
            --resume "$sid" --permission-mode dontAsk --max-turns 1 2>/dev/null)
    if printf '%s' "$out" | grep -q "4712"; then
        record PASS "--resume 保有前一輪的 context" "$(printf '%s' "$out" | oneline)"
    else
        record FAIL "--resume 保有前一輪的 context" "$(printf '%s' "$out" | oneline)"
    fi
else
    record SKIP "--resume 保有前一輪的 context" "第一輪未取得 session_id"
fi

# ---------------------------------------------------------------------
head2 "8. I4：.claude/agents/ subagent 是否載入"
table_header

mkdir -p .claude/agents
cat > .claude/agents/tester.md <<'AGENT'
---
name: tester
description: 測試用 subagent，用來確認 .claude/agents 有被載入。
tools: Read, Grep, Glob
---
你是測試用 subagent。回覆時必須包含「SUBAGENT-LOADED」這個字串。
AGENT

json=$("$CLAUDE_BIN" -p "列出目前可用的 subagent 名稱。" \
        --output-format stream-json --verbose \
        --permission-mode dontAsk --max-turns 1 2>/dev/null \
        | head -5)

if printf '%s' "$json" | grep -q "tester"; then
    record PASS "不帶 --bare 時 .claude/agents/ 會被載入" "system/init 中出現 tester"
else
    record SKIP "不帶 --bare 時 .claude/agents/ 會被載入" "未在前幾則事件中觀察到，需人工確認"
fi

# ---------------------------------------------------------------------
head2 "9. --max-turns 是否生效"
table_header

start=$(date +%s)
"$CLAUDE_BIN" -p "逐一讀取這個目錄下的每一個檔案，然後詳細說明每個檔案的內容。" \
        --permission-mode dontAsk --allowedTools "Read,Grep,Glob" \
        --max-turns 1 >/dev/null 2>&1
elapsed=$(( $(date +%s) - start ))
record INFO "--max-turns 1 的執行時間" "${elapsed}s（若明顯短於無限制的情況即為生效）"

# ---------------------------------------------------------------------

{
  echo
  echo "---"
  echo
  echo "## 總計"
  echo
  echo "- PASS：$PASS"
  echo "- FAIL：$FAIL"
  echo "- SKIP / INFO：$SKIP"
  echo
  if [ "$FAIL" -gt 0 ]; then
      echo "**有 $FAIL 項主張未通過。**"
      echo "依照評估報告的原則：實測與報告不符時，要修正的是報告，不是現實。"
      echo "把這份報告回傳，我會據此修正 architecture-review.md 與參考實作。"
  else
      echo "**所有主張都通過。** 評估報告的 CLI 契約部分可以直接採用，繼續進行 Phase 1。"
  fi
} >> "$REPORT"

log ""
log "報告已產出：$REPORT"
log "PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
