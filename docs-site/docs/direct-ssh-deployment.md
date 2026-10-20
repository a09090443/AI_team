# Agent VM 以 SSH 直連部署

## 規格與目前狀態

使用者指定日常路徑為 **Slack → Agent VM → SSH 公鑰認證 → dev VM**，不依賴 Windows broker 或 Guest Control。
規格單一來源在 `.agents/skills/service-build/SKILL.md` 及 `references/host-setup.md`，同步到 Claude 與 Slack runtime 套件。
2026-10-19 已啟用 Agent VM 的固定 SSH 部署執行器；Windows 不需執行 broker。收尾時 dev VM 曾無回應，使用者核准強制重啟後已恢復並重新驗證 HTTP200（詳見下方異常紀錄）。
兩台 VM 以共享 NAT Network `ai-team-direct`（172.30.46.0/24）互通：Agent `172.30.46.10`、dev `172.30.46.20`。
原 Windows 管理入口 127.0.0.1:2223／2224 與產品 127.0.0.1:8088 保留；這些入口不參與 Agent 日常部署。
維護時三個實例均無執行中任務，先備份資料庫、優雅停止服務與關機，再切換網路；所有任務資料保持一致。
專用 Ed25519 私鑰只留 Agent VM，dev 登錄 restrict／forced-command 公鑰與限定 sudoers；主機公鑰由可信 VM 管理介面取得並固定指紋。

`ai-team-ssh-deploy.service` 為 active／enabled，Agent linger=yes，未崩潰重啟；2026-10-20 使用者重啟後已確認新執行器自動 active/enabled；三個 orchestrator 仍依既有規則以 service.sh 手動恢復。

## 執行方式與權限

角色仍只呼叫固定 `client.py prepare|start|health|diagnose`，寫入原任務工作區的請求。
`ssh_executor.py` 是 Agent VM 的 systemd 使用者服務：查詢本機資料庫，僅接受目標相符、正在執行的 SERVICE 節點。
它在同一台 Agent VM 執行固定 `ssh_client.py`，直接連目標 VM，不需要 Windows 程式輪詢。
這是為保留角色沙箱與私鑰管理界線；未把角色改成可任意 SSH 或執行 root shell。

SSH 固定 BatchMode、公鑰認證、獨立 known_hosts 與嚴格指紋檢查；沒有 Windows 或密碼 fallback。
目標公鑰帶 `restrict,command=...`；sudoers 只允許 root-owned `ai-team-ssh-service` 固定入口。
目標只接受 target、prepare/start/health/diagnose、請求 ID 與 start 來源 SHA256，不接受模型自訂 shell。
部署來源為已授權任務的已提交HEAD。目標核對傳輸SHA256與壓縮檔結構，不再使用逐版source-manifest.json清單，也不另要求管理者核准正常版本更新。

請求 ID、target、action、來源 digest 必須與收據完全相符；成功還須有對應完成標記與整數結束碼。
相同 ID 已完成就取回收據，不重建；不確定中斷保留 running 標記與原請求。
舊 Windows worker 已接單但沒有收據時不能直接換通道重做，須核對舊現場。
歷史成功只作背景，本次需實際啟動與健康檢查；回報列程式／服務名稱、版本、目標、網址和限制。

## 一次性啟用

1. 等現有任務停止執行後安排網路維護。建立 VM 間的 Internal／Host-only 或共享 NAT Network，確認位址不同且互通；保留原有管理入口與產品資料。
2. Agent VM 保留專用私鑰（600），只把公鑰交目標管理者。從 dev VM 本機取得 SSH 主機公鑰並核對指紋，再登錄 Agent VM 的專用 known_hosts。
3. 將工具與Agent公鑰傳到 dev，以管理者權限執行 `install-ssh-target.sh <既有登入帳號> <Agent公鑰檔> bestpartner-dev`。不新增任意 sudo 或 root shell。
4. 管理者依 `assets/direct-ssh.example.json` 在 Agent VM 放 `~/.config/ai-team/ssh-targets/bestpartner-dev.json`，填真實互通位址、私鑰與 known_hosts 引用。
5. 依 `assets/ai-team-ssh-deploy.service` 安裝使用者服務，核對實例路徑後 `systemctl --user daemon-reload`、`systemctl --user enable --now ai-team-ssh-deploy.service`。沿用 INSTALL 的 linger 前置條件。
6. 驗證公鑰登入、錯誤指紋拒絕、固定入口拒絕任意命令、傳輸毀損與不合法壓縮檔拒絕，以及重啟後執行器自動可用。此時 Windows broker 應維持停止。
7. 更新服務提示、skill 與非機密恢復說明後，在 Slack 以原任務／受控配置更新或承接完成 prepare、start、health。舊快照若仍要求 Windows，不直接改資料庫或偽造核准。

只有兩台 VM 的上述實測成功才可標為已啟用；Python 單元測試不是網路、公鑰或部署端對端證據。
不在此流程中自動清空資料卷、更換資料庫密碼；正常版本更新不需維護來源清單。


## 2026-10-20 自主部署修正

使用者明確取消額外逐版清單關卡。SSH目標身分與固定部署授權仍有效，正常更新不需要第二次人工核准。安裝工具不再要求manifest，既有target.json的source_manifest欄位與舊檔案不再讀取，保留歷史備份不會阻擋新來源。
agent使用已提交且乾淨的工作區自行prepare、start、health與修復；只在缺少真正的主機權限、登入或任務範圍時請使用者處理，不要求維護清單。SHA256用於傳輸完整性與收據綁定，保留固定請求ID與避免重複啟動的機制。
#1048更新工具後可在原討論串重新部署，不需建立新任務或變更任務快照；系統注入的service-context提供最新實際能力說明，舊拒絕收據僅供歷史。完成必須取得新的START_PASS與HEALTH_PASS。

### 上線與驗證

- 2026-10-20 已更新 dev 的固定入口及target.json，取消source_manifest讀取；目標備份 `/var/lib/ai-team-service/tool-backup-20261020-134631`。舊清單只留歷史，不參與執行。
- Agent 固定工具、service/service-triage提示、skill及最新service-context已更新，bestpartner重載新配置 `d1334fe3…`；備份 `~/ai-team-bestpartner/autonomous-deploy-backup-20261020-134803`。完整六張任務表與所有工作區HEAD/status/diff前後一致，沒有改任務快照或核准。
- 這次只重載部署工具與提示，產品來源和驗證指令未變；僅該次啟動以 `AI_TEAM_BASELINE_VERIFY=off` 避免重跑無關產品基準，未改env.sh/team.env或預設值。
- Windows/Linux各36項Python測試、Java75項相關測試、check-source與docs build通過。用#1048真實封裝 `a0c53284…` 驗證來源格式可接受而不需清單；這不是本次產品start成功證據。
- 服務啟動與沙箱自檢通過、ERROR0，SSH執行器active且NRestarts0；新health收據 `ab1c03e97bcf48018a563e70d94e2a11` exit0，PostgreSQL/後端正常，Windows8088 HTTP200。
- #1048仍AWAIT_HUMAN，未自動發Slack訊息或續跑、未重部署產品；可由原討論串補充「部署清單限制已移除，請重新執行部署與健康檢查」續跑，無需管理者更新清單或另開新任務。Slack新一輪start到結案仍待實測。

## 歷史紀錄（以下逐版清單流程已於2026-10-20取消）

## 2026-10-19 實測與承接

- 實作來源 `f79f142`，Linux Maven 687 項、零失敗／錯誤、11 略過；Python 30 項與 check-source 通過。
- Agent → dev 真實 SSH：health 收據 `7a4cc6a9ad234113a98ea482d8b81e6c`，PostgreSQL／後端執行中，`/view/chat` HTTP 200；diagnose `1592ae09b17a460ea402fda65ee44930` 成功。
- 任意 `id` 命令被固定入口拒絕、未登錄主機指紋被 SSH 拒絕；合法 archive 的未核准檔案被 manifest 拒絕（`341d12cd99be4627bdf4b1fa1a0a1d75`），未啟動產品；重取健康請求得到完全相同收據。
- 網路維護前備份 `~/direct-ssh-maintenance-20261019-131926`；三實例 task／flow_run／task_checkpoint／direct_request／stage_result／quota_retry 逐列一致。
- 本輪未重建／重部署產品，也未送 Slack 訊息或續跑任務；不是新的 Slack 部署端對端成功證據。
- #1046 保存舊版 skill 快照，變更 skill 需新任務承接，不直接改快照。請在 bestpartner 頻道 @PM：
  「承接 #1046，在既有 bestpartner-dev 部署測試程式。依目前 SSH 直連規則，先查部署歷史與目前健康，再執行核准的 prepare、start、health；不要清空資料卷。完成請回報版本、服務名稱與可用網址。若來源與既有核准 manifest 不符，先列出差異並停止。」
- Agent 維運：`systemctl --user status ai-team-ssh-deploy.service`；需要恢復才用 `systemctl --user restart ai-team-ssh-deploy.service`，不需開啟 Windows 程式。


### 收尾時 dev VM 異常

13:28:55 UTC 左右，VirtualBox `VBox.log` 記錄 `Guest seems to be unresponsive`；之後 Agent ping／HTTP 與 Guest Control 均無回應，Windows 8088 也逾時。此前直連 health／diagnose 與拒絕測試均已完成成功。
VM 狀態仍 running，不把先前 HTTP200 當成目前健康，也不認定為單一 port-forward 問題。已送 ACPI 正常關機，未強制 reset／poweroff、未清除資料卷。使用者明確核准後已對 dev 執行 reset；恢復後新健康收據 `d443693b701e4555b8dd084178e39628` 成功，PostgreSQL/後端正常，Agent SSH 與 Windows http://127.0.0.1:8088/view/chat 均 HTTP200。上次核心日誌有 RCU/clock/watchdog 與既有 VirtualBox 顯示驅動警告，未見足以確認的當機根因，不能宣稱已根治；未清空資料卷。
bestpartner orchestrator 已安全升級：JAR `45879bfd7672ecb88d63798fb02293235d57b1337bd986c4508bdbbd9edda7ee`，PID8844，備份 `~/ai-team-bestpartner/orchestrator-backup.II8thl`；部署腳本完整任務／工作區快照比對通過。Agent 執行器 active／enabled，restart 驗證成功，Windows broker 程序數量0。


### #1047 來源封裝受阻（2026-10-19）

#1047 已通過 prepare 與 diagnose，SSH 執行器正常；start 三次失敗後停 AWAIT_HUMAN。
工作區基準 dev `2216d6d` 沒有版控的 `docs/docker/sit`，固定 `git archive HEAD bestpartner-service docs/docker/sit docs/sql` 因 pathspec 不存在而失敗，尚未傳送來源到 dev。
先前執行器只產生「無法取得SSH收據」的通用訊息，誤導為網路／公鑰問題。
已在 start 封裝前加入版控路徑預檢：缺件回 `SOURCE_MISSING_PATHS`，未提交變更回 `SOURCE_WORKSPACE_DIRTY`，超量回 `SOURCE_TOO_LARGE`，持久化具體失敗收據，不嘗試 SSH。
缺件需將經審閱的部署檔正式加入核准來源並提交，再比對目標的來源 manifest；不能跳過缺少路徑、直接拿舊容器當本次成功，或自動覆寫 manifest。#1047 未自動續跑、未改任務狀態／歷史收據。

診斷修正已更新至 bestpartner 的固定 ssh_client.py（備份 `ssh-preflight-backup-20261019-140920`），執行器 active；Windows／Linux 各33項Python測試、check-source與文件建置通過。#1047 真實工作區唯讀重現缺件，未續跑任務或重新部署產品。


### #1048 目標來源核准受阻（2026-10-20）

最新 bestpartner 任務 #1048 停在 AWAIT_HUMAN。工作區 HEAD `72f694b` 乾淨，已含 `docs/docker/sit/docker-compose.yml` 與 `00-schema.sql`；#1047 的缺檔問題已不再是本次卡點。
本次封裝SHA256為 `a0c53284074fff3b35e97d6b7444744a2b02bf2a5b8ba2a837e31e1be7057dd8`。prepare、health與diagnose均exit0，start兩次回 `Source approval or target setup check failed; data retained`（f66d63fe…、03a0f745…）。
可確認已到目標核准／設定檢查，不能視為新版本部署成功；現有health HTTP200只代表既有服務可用。PM判斷舊manifest尚未更新，但本次僅唯讀查任務／工作區／收據，未以root檢查或更新目標manifest，因此尚未獨立排除其他目標setup檢查失敗。
下一步由管理者核對目標manifest與已審閱來源差異，再決定更新核准清單；未更新前重按部署會再被拒絕。不得自動覆寫清單、改任務狀態或用健康收據替代start成功。
