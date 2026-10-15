// 公開手冊清單：側欄、搜尋與 AI 輸出共用。
export const manual = [
  {id:'index',title:'AI Team 介紹',route:'intro',group:'認識專案',keywords:'Slack Java Spring Boot 角色 協作',summary:'專案目標、讀者路線與能力邊界。'},
  {id:'quickstart',title:'第一個 Slack 任務',route:'quickstart',group:'使用指南',keywords:'開始 需求 範例 核准 PR',summary:'從需求到核准 PR 的使用路線。'},
  {id:'usage',title:'操作與角色對話',route:'usage',group:'使用指南',keywords:'指令 按鈕 中斷 中止 執行 mention',summary:'Slack 指令、按鈕與角色派工。'},
  {id:'troubleshooting',title:'任務停住時怎麼辦',route:'troubleshooting',group:'使用指南',keywords:'故障 排隊 額度 WAITING_QUOTA 狀態 登入',summary:'等待、帳號、配置與發布問題排查。'},
  {id:'installation',title:'安裝與驗證',route:'installation',group:'管理與設定',keywords:'Linux bootstrap 安裝 部署 升級 preflight',summary:'環境前置、安裝順序與分層驗收。'},
  {id:'configuration',title:'Slack 與團隊設定',route:'configuration',group:'管理與設定',keywords:'App token 環境變數 JSON teams 角色',summary:'Slack App 與配置式團隊結構。'},
  {id:'architecture',title:'架構概要',route:'architecture',group:'架構與開發',keywords:'orchestrator runtime CLI 沙箱 SQLite 資料模型',summary:'系統分層、資料責任與程式碼地圖。'},
  {id:'workflow',title:'工作流程',route:'workflow',group:'架構與開發',keywords:'需求 設計 測試 驗收 審查 交接 核准 快照',summary:'節點、返工、人工閘門與交付邊界。'},
  {id:'development',title:'開發與文件維護',route:'development',group:'架構與開發',keywords:'修改 Maven build 文件 規則 測試',summary:'修改位置、驗證與文件同步規則。'},
  {id:'ai-guide',title:'AI 閱讀入口',route:'ai-guide',group:'AI 入口',keywords:'AI agent llms 索引 程式碼 契約 證據',summary:'閱讀順序、任務路由與證據判讀。'},
];
