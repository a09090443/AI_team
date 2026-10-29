# SSH 金鑰免密碼登入

適用於新 VM 的預設佈建，以及使用者要求的既有 VM SSH 設定。VBoxManage 只能控制 VM，不會自動在 guest 安裝 SSH server 或加入公鑰；必須使用映像支援的 cloud-init／安裝器、已授權的 guest 連線、console，或具備 Guest Additions 與合法 guest 憑證的 guestcontrol 進行初始佈署。沒有初始存取管道時回報缺少的條件，不能只建立主機金鑰就宣稱可登入。

## 主機金鑰

1. 查找 OpenSSH client、`ssh-keygen`，確認本次 VM UUID 與 guest 帳號。使用者已指定的金鑰／SSH agent 優先；Vagrant VM 改用 `vagrant ssh-config`，不列印私鑰內容。
2. 未指定金鑰時，在主機目前使用者的 `.ssh` 下使用 `virtualbox/id_ed25519_VM_UUID` 作為專用路徑，以 UUID 避免 VM 名稱含特殊字元。建立前先確認父目錄屬於目前使用者且不可由一般其他使用者寫入，拒絕不明 symlink／reparse point；先設限權再產生金鑰。確認私鑰及 `.pub` 是否存在，既有檔案不覆寫；若只有私鑰，可用 `ssh-keygen -y -f KEY_PATH` 取得對應公鑰，不能另產生不相符的 key pair。
3. 非 Vagrant VM 預設用 `ssh-keygen -t ed25519 -f KEY_PATH -C virtualbox:VM_UUID` 讓使用者透過互動輸入 passphrase，並將金鑰載入主機可用的 SSH agent；不在聊天、命令列或檔案中記錄 passphrase，也不自行安裝／啟用系統服務。已有可用 agent／金鑰直接沿用。只有無人值守需求且沒有可用 agent 時，才使用受限權限的專用空 passphrase 金鑰並記錄取捨，不將空 passphrase 視為免密碼登入的必要條件。環境無法安全互動或缺少必要權限時，回報尚缺的條件，不自行降級。
4. 空 passphrase 的參數語意為 `ssh-keygen -t ed25519 -f KEY_PATH -N '' -C virtualbox:VM_UUID`。Windows PowerShell 舊版可能遺失空引數，使用能保留空引數的呼叫方式，例如可用 Python 的 `subprocess.run(['ssh-keygen', '-t', 'ed25519', '-f', key_path, '-N', '', '-C', comment], check=True)`。Vagrant VM 例外沿用其自動產生的專用金鑰，不另外建立或移除 passphrase；檢查預設公開測試金鑰已替換。
5. POSIX 私鑰設為 `0600`、存放目錄 `0700`；Windows 檢查 ACL，僅目前使用者與必要系統管理身分可讀，不給 Everyone／Users 一般讀取權。金鑰建立後再次驗證權限。私鑰不放入專案、版本庫、共享資料夾、cloud-init 或 guest，Vagrant 管理目錄內的專用金鑰須同樣限權並排除版控／同步。不要更動其他既有金鑰的權限或 passphrase。

## Guest 與網路

- Linux guest：沿用映像內既有一般使用者，確認 OpenSSH server 與服務。新 VM 的預設佈建包含安裝／啟用所需 SSH server；依實際發行版選套件與服務名稱，不假設每個系統都用 `apt` 或 `ssh.service`。既有 VM 只做本次 SSH 設定所需變更。
- 將單行公鑰加入該帳號的 `~/.ssh/authorized_keys`，先檢查是否已存在，保留原有金鑰與限制選項。確保檔案屬於該帳號，Linux `.ssh` 為 `0700`、authorized_keys 為 `0600`。公鑰以資料傳入，不拼接成未引用的 shell 指令。
- Windows guest：使用其 OpenSSH Server 的帳號、authorized_keys 路徑與 ACL 規則；管理員帳號可能使用 `administrators_authorized_keys`，不能套用 Linux chmod 範本。依 guest 版本的 Microsoft 官方文件確認。
- 確認 server 允許公鑰驗證；必要時驗證 sshd 設定再 reload，保留可用的初始連線。不要因免密碼需求自行停用原有密碼登入或改 sudo 權限。
- 新 VM 預設 NAT 轉發主機 `127.0.0.1:2222` 到 guest `22`；2222 已用則選擇其他可用埠並記錄。既有可用且符合需求的規則直接沿用，不覆寫同名規則、不綁 `0.0.0.0`。依已安裝版本核對 `modifyvm`／`controlvm` 的 NAT port forwarding 語法。
- guest 防火牆有封鎖時，僅加入所需介面／來源的 SSH 規則，不關閉防火牆。使用者要求離線或不開 SSH 時優先遵守。

## 驗證與交付

先從 console、安裝器輸出或其他已驗證管道取得 guest SSH host key 指紋，再核對連線端點並加入 known_hosts；`ssh-keyscan` 單獨取得的結果不足以證明身分。遇到 host key 變更先確認是否重建／換機，不自動刪除舊紀錄，也不設定 `StrictHostKeyChecking=no` 或捨棄 known_hosts。

用實際值取代下列佔位參數，並以獨立引數傳入：

```text
ssh -i KEY_PATH -p HOST_PORT -o IdentitiesOnly=yes -o BatchMode=yes -o PreferredAuthentications=publickey -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no -o StrictHostKeyChecking=yes -o ForwardAgent=no -o ForwardX11=no -o ClearAllForwardings=yes -o ControlPath=none -o ConnectTimeout=10 GUEST_USER@HOST_ADDRESS whoami
```

退出碼必須為 0，且 `whoami` 對應預期帳號，才算完成免密碼登入。這項驗證同時禁止互動詢問密碼、意外的 agent／X11／埠轉發，並停用連線重用以確保重新驗證目前金鑰。帶 passphrase 的金鑰須已載入使用者指定的 agent；主機使用 agent 不需要將 agent 轉發到 guest。逾時或失敗就診斷網路、服務、帳號與金鑰權限，不退回密碼登入充當驗證成功。

可建立專用 SSH Host alias，例如 `vbox-UUID`，包含 HostName、User、Port、IdentityFile、IdentitiesOnly，並明確設定 `ForwardAgent no`、`ForwardX11 no`、`ClearAllForwardings yes`、`StrictHostKeyChecking yes`。修改前檢查衝突與順序（OpenSSH 多數設定以先取得者優先），保留其他設定，用 `ssh -G` 核對有效值；驗證連線仍須停用既有連線重用。Vagrant 的 ssh-config 僅取連線資料，移除停用 host key 驗證的選項，不直接寫入主機全域設定。不得在 SSH config 中寫入密碼。交付時提供實際連線命令或 alias、帳號、埠、公鑰指紋、私鑰路徑及驗證結果，不輸出私鑰內容。

## 複製／匯入後作為獨立 VM

單純複製／匯入時保留關機，不擅自修改 guest。若使用者要求佈建獨立環境，先盤點繼承的 SSH host keys、authorized_keys 與網路身分；VM UUID 或 MAC 改變不會自動更換 guest SSH 身分。

在只允許本機管理的網路與可信 console／初始化管道中，只對新 VM 依發行版程序重新產生 host keys，從獨立管道核對新指紋後登錄新端點；不要修改來源 VM、還原備份原應保留的身分，或直接清除整份 known_hosts。將新 VM 的專用公鑰加入預期帳號，逐項確認繼承授權的用途；移除明確屬於來源 VM 的舊管理公鑰／公開測試金鑰，其他不明授權先回報並釐清，不能為了保留檔案而宣稱已隔離權限，也不能清空全部 authorized_keys。完成獨立 SSH 驗證及必要 IP／服務身分處理後，才依需求啟用其正常網路。

## 官方參考

- [OpenSSH ssh-keygen](https://man.openbsd.org/ssh-keygen)
- [OpenSSH ssh](https://man.openbsd.org/ssh)
- [OpenSSH sshd 與 authorized_keys](https://man.openbsd.org/sshd)
- [OpenSSH client 設定與轉發](https://man.openbsd.org/ssh_config)
- [Microsoft：Windows OpenSSH 金鑰管理](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_keymanagement)
