# 各來源格式注意事項

## claude.ai Design 畫布（`.dc.html`）

- `project/canvas.json` 的 `boards` 列出所有畫板，`title` 是畫板名稱，`notes` 的 `title1` 是分組標題
  （例如「現況」「重新設計」）。依分組判斷哪些是新設計、哪些只是現況記錄。
- 每個畫板是 Design Component 格式，不是可直接執行的 HTML：
  - `<script src="./support.js">` 是畫布的執行時，設計包裡移除。
  - `<x-dc>` 內是畫面；`<helmet>` 內是字型 `<link>` 與基本樣式。
  - `{{name}}` 從檔尾 `<script type="text/x-dc">` 的 `renderVals()` 取值；`<sc-for list="{{rows}}" as="r">` 是迴圈，
    `<sc-if value="{{x}}">` 是條件。`renderVals()` 裡的陣列通常是示意資料。
  - `data-props` 的 `$preview` 是畫板尺寸；畫板的 `expand: "fill"` 代表是流動版面的網頁，不是固定尺寸。
- 保留原檔到 `screens/<畫板名>.dc.html`，並在 README 說明上述語法，agent 就能讀懂；不必手動展開成靜態 HTML。
- 樣式都寫在 inline `style`，色碼與尺寸可直接抽成 tokens.json；有「樣式規格」類畫板時以它為 tokens 主要來源。
- 畫板是靜態示意，常把互斥狀態並列（成功卡與失敗 alert）或用示意資料預設狀態（`sel: true` 讓批次列出現、toast 常駐）；
  從 `renderVals()` 的條件找出這些狀態，寫進 README 的「畫面中同時出現的狀態」。
- 畫布沒有截圖檔；需要截圖時請使用者從畫布匯出，或在 README 註明「無截圖，以 HTML 為準」。

## 一般網頁

- 存下的 HTML 只當結構與樣式參考：移除 `<script>`、追蹤碼與第三方嵌入；外部 CSS 有需要時取出相關規則寫進 README。
- 需要登入的頁面只在使用者已登入的瀏覽器操作，不代為登入，存檔前確認沒有個資或 token。

## 截圖／圖片

- 只能目測的數值（色碼、間距、字級）在 README 標「目測」，交由實作角色以既有設計系統對齊。
- 一張圖一個畫面，檔名與 README 的畫面標題一致。

## Figma

- 授權後以 figma MCP 讀節點的樣式、變數與匯出圖；元件名稱與 variant 寫進 README，方便對應前端元件。
- 未授權時不要嘗試抓取網頁版 Figma，請使用者匯出或授權。
