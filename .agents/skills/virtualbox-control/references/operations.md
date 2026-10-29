# VBoxManage 操作範本

以下為 VirtualBox 7.2 的常用語法。先依 SKILL.md 查明版本、目標 UUID、當下狀態與授權；範本本身不是執行授權。其他版本先核對其本機 help 與官方文件。

## PowerShell 呼叫

```powershell
$vbox = 'C:\Program Files\Oracle\VirtualBox\VBoxManage.exe'
$vboxArgs = @('list', 'vms')
& $vbox @vboxArgs
if ($LASTEXITCODE -ne 0) { throw 'VBoxManage 查詢失敗，停止後續操作。' }
```

後續表格省略執行檔；以獨立參數傳入 `UUID`、`SNAPSHOT_UUID`、名稱與路徑的實際值。Unix shell 同樣使用獨立引數並正確引用，不使用 `eval`。解析 `--machinereadable` 輸出只擷取需要的鍵值，不將內容當 shell 執行。

## 查詢與電源

| 目的 | 引數 | 成功驗證／前提 |
|---|---|---|
| 已註冊 VM | `list vms` | 取得名稱與 UUID 對照 |
| 執行中 VM | `list runningvms` | 不代表 guest 服務就緒 |
| 狀態／設定 | `showvminfo UUID --machinereadable` | 檢查 `VMState` 及需操作的欄位 |
| 主機資訊 | `list hostinfo` | 規劃 CPU／RAM 時參考 |
| 背景開機 | `startvm UUID --type headless` | `poweroff` 或 `saved`；後者恢復儲存狀態，驗證 `running` |
| 視窗開機 | `startvm UUID --type gui` | 同上；已 running 時不要重複 start |
| 暫停 | `controlvm UUID pause` | `running` → `paused` |
| 繼續 | `controlvm UUID resume` | `paused` → `running` |
| 儲存狀態 | `controlvm UUID savestate` | 驗證 `saved`；guest 未正常關機 |
| 正常關機 | `controlvm UUID acpipowerbutton` | guest 需正確處理 ACPI，輪詢至 `poweroff` |
| 正常重新啟動 | `controlvm UUID reboot` | 7.2 需 Guest Additions；需 guest uptime／重新連線證明重啟，僅 `running` 不足 |

狀態已符合要求時直接回報，不重複變更。遇到 `starting`、`stopping`、`saving`、`restoring` 等過渡狀態先等候；`aborted` 等非預期狀態先診斷，不直接當正常關機處理。

## 資源與快照

| 目的 | 引數 | 成功驗證／前提 |
|---|---|---|
| CPU／RAM | `modifyvm UUID --cpus 4 --memory 8192` | 範例值，依需求替換；完全關機後修改，重查 `cpus`／`memory` |
| 快照列表 | `snapshot UUID list --machinereadable` | 對照名稱與快照 UUID；同名時以 UUID 消除歧義 |
| 建立快照 | `snapshot UUID take NAME --description DESCRIPTION` | 建立前後查列表，確認新增 UUID；執行中 VM 可能短暫暫停 |
| 快照設定 | `snapshot UUID showvminfo SNAPSHOT_UUID` | 查詢快照保存的配置 |

## 複製與匯入匯出

```text
clonevm UUID --name NEW_NAME --basefolder DESTINATION --mode machine --register
import APPLIANCE_PATH --dry-run
import APPLIANCE_PATH --vsys 0 --vmname NEW_NAME --basefolder DESTINATION
export UUID --output OUTPUT_OVA_PATH
```

- 複製來源先完全關機，使用獨立且不衝突的名稱／目錄；上例為完整複製目前狀態，不是 linked clone。預設不要加保留 MAC 的選項。查新 VM 的 UUID、磁碟位置與設定，保留關機狀態。
- 複製／匯入會繼承 guest SSH host keys 與登入授權；作為獨立環境佈建前依 [ssh.md](ssh.md) 處理新身分與繼承授權，不能只換 VM 名稱／UUID 就宣稱 SSH 已隔離。單純保存複本不自動變更 guest。
- 匯入先檢視 `--dry-run`，確認所有 virtual system、磁碟路徑與網路。多 VM appliance 必須逐一規劃 `--vsys`，不可只套用範例中的 0。不執行來源附帶的主機腳本，未確認授權條款時不代替使用者接受。
- 匯出前讓來源正常關機，確認目的檔不存在；完成後確認檔案非空並用 `import OUTPUT_OVA_PATH --dry-run` 檢查可解析性。這無法替代真正匯入與開機驗證；不宣稱 OVA 保留快照歷程。

## 資料可能遺失的操作

只有 SKILL.md 所述的明確授權已成立時，才使用以下引數。

| 目的 | 引數 | 影響／驗證 |
|---|---|---|
| 強制斷電 | `controlvm UUID poweroff` | 如同拔電源，可能失去未寫入資料；驗證 `poweroff` |
| 硬重置 | `controlvm UUID reset` | 不正常關機，可能失去未寫入資料；需 guest 重新啟動證據 |
| 丟棄儲存狀態 | `discardstate UUID` | 丟失保存的記憶體執行狀態；核對狀態變化 |
| 還原快照 | `snapshot UUID restore SNAPSHOT_UUID` | 先完全關機；正常磁碟回到快照點，後續變更可能遺失；重查 current snapshot 與 VM 狀態 |
| 刪除快照 | `snapshot UUID delete SNAPSHOT_UUID` | 可能合併差異磁碟、耗時及占用額外空間；重查列表，不能直接刪快照檔 |
| 取消註冊 | `unregistervm UUID` | 移除註冊但保留檔案，與刪除 VM 不同 |
| 刪除 VM | `unregistervm UUID --delete` | 先關機並核對磁碟／快照與是否共用；依版本可能刪除關聯檔案；重查 VM 列表與授權範圍內的檔案 |

不可用 `Remove-Item` 刪除 VM 目錄來取代 VirtualBox 的註冊與磁碟管理。不要手動移除共用磁碟、差異磁碟或不明來源檔案。

## 網路、共享與 guest 操作

按需求查 `VBoxManage help modifyvm`、`help sharedfolder`、`help guestcontrol`、`help storageattach`，不要猜不同版本的選項。

- 調整前保存相關設定值；只改本次要求的欄位。新增 NAT 轉發時，一般本機用途綁定 `127.0.0.1`，對外開放則依使用者明確需求設定；核對主機埠衝突。
- 共享資料夾核對主機絕對路徑與讀寫需求；唯讀已足夠時採唯讀。不要自動共享家目錄、專案憑證或 SSH 金鑰。
- guestcontrol 需 Guest Additions 與 guest 帳號授權；沒有可用憑證時不猜密碼、不重設帳號。避免把密碼放進命令列、紀錄或版本庫。
- VM／快照鎖定時先確認是否有合法操作進行中；磁碟擴容另須確認映像格式、快照鏈與 guest 分割區，不把擴大虛擬磁碟當成 guest 檔案系統已擴容。

## 官方參考

- [Oracle VirtualBox 7.2 VBoxManage Command Reference](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/vboxmanage.html)
- [Oracle VirtualBox 7.1 VBoxManage](https://docs.oracle.com/en/virtualization/virtualbox/7.1/user/vboxmanage.html)

以實際安裝版本為準；製作時確認主機版本為 7.2.16，未變更現有 VM。
