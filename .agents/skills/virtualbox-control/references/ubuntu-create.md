# 建立 Ubuntu 開發／測試 VM

本流程整合原 `virtualbox-ubuntu`，不依賴舊 skill 或第三方 vagrant-skill 腳本。使用 Vagrant 與 VirtualBox 建立 VM；不宣稱 VM 完全隔離或主機完全不受影響。

## 準備與映像

- 確認 Vagrant、VBoxManage 路徑、版本及彼此相容性；核對主機 CPU 架構。工具不存在時回報，不自動安裝軟體、外掛、重開機、改 BIOS 或關閉 Hyper-V。
- 沿用使用者指定的名稱、目錄、CPU、RAM、磁碟與用途。未指定資源時採 2 CPU、4096 MB RAM，檢查主機可用資源；管理目錄使用專用空目錄，先將具體路徑與配置告知使用者。已有明確建立授權且位置可合理決定時可繼續；路徑無法判定、空間不足或設定衝突才詢問。
- 先查 VM 名稱、目的目錄與 Vagrant 狀態，不覆寫既有 Vagrantfile／VM。Vagrant 管理目錄與 VirtualBox 磁碟目錄可能不同；建立前確認兩者位置與容量。沒有指定磁碟容量時沿用已確認的 box 預設，不宣稱配置固定容量；需要擴容時核對已安裝版本支援，不偷偷安裝磁碟外掛。
- 預設候選為 `bento/ubuntu-24.04`，它是 Bento 社群映像，不是 Canonical 官方映像。建立當下從發行者的版本資料確認該名稱、VirtualBox provider、主機架構與精確版本；無法核實就停止下載，不猜版本或改抓不明 ISO。
- 將精確版本寫入 `config.vm.box_version`，不使用 latest 或範圍版本。記錄來源、版本、架構與可用 checksum 並驗證下載；checksum 只證明內容一致，不能取代來源信任。來源更新須重新檢視，不自動更新既有 box。
- Vagrantfile 是主機可執行 Ruby。只產生已檢視的最小設定，不執行來源附帶或未知管理目錄的 Vagrantfile。檢視 box 的內建設定，確認沒有額外共享、網卡或不預期的主機動作。

## 產生 Vagrantfile

在管理目錄寫入完整設定，將實際名稱與已驗證版本以正確 Ruby 字串編碼填入，不把使用者輸入直接拼接成 Ruby 程式。以下為必要設定片段，須放入 `Vagrant.configure("2") do |config| ... end`；另外設定 `config.vm.box`、精確 `config.vm.box_version`、provider 的 `name`、`cpus`、`memory` 與 `gui = false`（使用者要求視窗時才為 true）。

```ruby
config.ssh.forward_agent = false
config.ssh.forward_x11 = false
config.ssh.insert_key = true
config.ssh.keys_only = true
config.ssh.verify_host_key = :accept_new
config.ssh.extra_args = [
  "-o", "ForwardAgent=no", "-o", "ForwardX11=no",
  "-o", "ClearAllForwardings=yes"
]
config.vm.synced_folder ".", "/vagrant", disabled: true
config.vm.network "forwarded_port", guest: 22, host: 2222,
  host_ip: "127.0.0.1", id: "ssh", auto_correct: true
```

`verify_host_key = :accept_new` 僅用於首次啟動的 trust on first use（TOFU），不是已核實主機身分。已有可信 host key 時直接使用 `:always`。首次使用時先說明此限制，只允許已核對來源的新建本機 NAT VM，並從 console 等獨立管道核對指紋；若用途要求首次連線前即驗證身分，先以映像支援的本機初始化／console 取得 host key 並預先登錄，不用 TOFU。指紋取得與核對完成後改成 `:always`；版本不支援時停止並說明，不能退回 `:never`。`extra_args` 只影響外部 SSH client，不能當成內部 communicator 的 host key 設定。

在 VirtualBox provider 中，依本機 help 核對後加入停用共享剪貼簿與拖放的 `customize`；VirtualBox 7.2 範例：

```ruby
vb.customize ["modifyvm", :id, "--clipboard-mode", "disabled"]
vb.customize ["modifyvm", :id, "--clipboard-file-transfers", "disabled"]
vb.customize ["modifyvm", :id, "--drag-and-drop", "disabled"]
```

- 預設只用 NAT，不新增 bridge、host-only、其他轉發埠或 nested virtualization。NAT 仍可連外且可能存取主機／區網服務，不能當成網路封鎖。
- 不加 `/project`、`/vagrant-scripts` 或任何其他共享資料夾。必要傳檔以指定非敏感檔案的單向複製處理；使用者要求共享時才設定精確路徑與讀寫範圍，不分享家目錄、SSH 私鑰、`.env` 或雲端憑證。
- 保留映像所需 SSH server 並驗證服務，不另加 Docker、Go、Node.js 等非必要套件。不執行 `curl | bash`，不用主機 shell provisioner、觸發器或任意主機命令。必要 provisioner 僅在 guest 做使用者要求的最小初始化。
- `.vagrant` 可能含未加密專用私鑰，管理目錄必須先設限權，並將 `.vagrant/`、私鑰與憑證排除版控及非必要同步。不要為加密金鑰直接改動 Vagrant 自動管理的認證流程；若使用者要求加密，依實際版本另行配置及驗證。

## 執行與驗證

1. 檢視完整 Vagrantfile，包括 box 內建設定與 provider customize；執行 `vagrant validate`。語法通過不代表主機共享與認證設定已符合需求。
2. 在已授權管理目錄執行 `vagrant up --provider=virtualbox`，每次查退出碼。失敗先讀原因，最多修正重試兩次；重試前查現有狀態，不刪 VM、不放寬設定或關閉主機防護。
3. 用 `vagrant status` 與 UUID 對應確認 VM；用 `VBoxManage showvminfo UUID --machinereadable` 核對磁碟位置、NAT、SSH 綁定 localhost 的實際埠、無額外共享／網卡、剪貼簿與拖放均停用。發現非預期主機暴露時停止後續 guest 工作並回報。
4. 依 [ssh.md](ssh.md) 核對 SSH host key 及金鑰登入。`vagrant ssh-config` 只取 HostName、Port、User、IdentityFile 等必要資料；不要直接沿用可能包含 `StrictHostKeyChecking no` 或捨棄 known_hosts 的輸出。確認 Vagrant 預設公開測試金鑰已從 guest 移除、目前 IdentityFile 是專用私鑰；不能只靠 `insert_key = true` 宣稱完成。
5. 核對 host key 後，Vagrant 設為 `verify_host_key = :always` 並驗證其實際 known_hosts 查找與連線結果。用嚴格 host key 驗證的非互動 SSH 執行 `whoami`、讀取 `/etc/os-release` 確認帳號與 Ubuntu 版本。若仍無法驗證 host key 或金鑰登入，明確回報未完成，不宣稱已安全建立。
6. 回報 VM 名稱／UUID、管理目錄、實際磁碟位置、box 來源／版本／架構、CPU／RAM／磁碟配置、連線方式及驗證結果。Vagrant 的初始測試金鑰、TOFU 與映像原有登入／sudo 設定有任何未消除限制，須如實說明。

## 後續管理與重建

在已檢視管理目錄，優先 `vagrant status`、`halt`、`up`、`suspend`、`resume`，設定修改同步維護 Vagrantfile。需要 `reload` 才生效時先說明會重啟 guest。不要任意 `vagrant provision`，其中可能有額外副作用。

建立後保留 VM；`vagrant destroy` 不放入測試收尾或失敗清理。只有明確授權刪除該 VM／指定可拋棄生命週期時，核對 UUID、管理目錄、快照與資料保存範圍才可 destroy。「重建」先保留來源或釐清替換範圍，不自動刪除既有 VM，也不操作其他 VM。

## 官方參考

- [Vagrant SSH 設定](https://developer.hashicorp.com/vagrant/docs/vagrantfile/ssh_settings)
- [停用預設共享](https://developer.hashicorp.com/vagrant/docs/synced-folders/basic_usage)
- [Box 版本固定](https://developer.hashicorp.com/vagrant/docs/boxes/versioning)
- [轉發埠與 host_ip](https://developer.hashicorp.com/vagrant/docs/networking/forwarded_ports)
- [VirtualBox VBoxManage](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/vboxmanage.html)
