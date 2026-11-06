package com.zipe.aiteam.slack;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用者附檔的 Markdown 輔助版本：PDF、docx、xlsx、xls、pptx 在 JVM 內轉成純文字 Markdown，
 * 讓不能直接讀 PDF／Office 的 runtime 也讀得到，並可用 grep 找段落。
 *
 * 轉換會失真（PDF 表格變散字、圖消失、掃描檔沒有文字層），所以只當輔助：原檔一律保留，
 * 檔頭註明以原檔為準。轉不出內容時不產生檔案，回傳原因讓系統告知 agent 直接讀原檔。
 */
public final class DocumentMarkdown {
    private DocumentMarkdown() {}

    /** 能轉的副檔名；doc、ppt、odf、rtf 等舊格式不轉，agent 直接讀原檔。 */
    public static final List<String> SUPPORTED = List.of("pdf", "docx", "xlsx", "xls", "pptx");
    static final int MAX_CHARS = 1_000_000;
    static final int MAX_SHEET_ROWS = 5_000;
    static final int MAX_SHEET_COLUMNS = 100;
    static final long TIMEOUT_SECONDS = 120;
    private static final Pattern HEADING = Pattern.compile("(?i)^(?:heading|標題)\\s*(\\d)$");

    public static boolean supports(String fileName) {
        String ext = extension(fileName);
        return ext != null && SUPPORTED.contains(ext);
    }

    /**
     * 把 source 轉成 Markdown 寫到 target（先寫暫存檔再改名）。
     * @return null 表示成功；否則是轉不出來的原因（不產生 target）
     */
    public static String convert(Path source, Path target, String displayName) {
        String ext = extension(source.getFileName().toString());
        if (ext == null || !SUPPORTED.contains(ext)) return "不支援轉換的格式";
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "document-markdown");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<String> body = worker.submit(() -> switch (ext) {
                case "pdf" -> pdf(source);
                case "docx" -> docx(source);
                case "xlsx", "xls" -> sheets(source);
                case "pptx" -> pptx(source);
                default -> throw new IOException("不支援轉換的格式");
            });
            String text;
            try { text = body.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); }
            catch (TimeoutException e) { body.cancel(true); return "轉換超過 " + TIMEOUT_SECONDS + " 秒"; }
            catch (ExecutionException e) { return reason(e.getCause()); }
            if (text.isBlank()) return "沒有可擷取的文字";
            String header = "> 系統從 `" + displayName.replace("`", "'") + "` 自動轉出的純文字版，方便搜尋與引用；"
                    + "表格、圖與版面可能失真或遺漏，內容有疑問時以原檔為準。\n\n";
            Path temp = Files.createTempFile(target.getParent(), ".convert-", ".part");
            try {
                Files.writeString(temp, header + text, StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "轉換被中斷";
        } catch (Exception e) {
            return reason(e);
        } finally {
            worker.shutdownNow();
        }
    }

    private static String reason(Throwable e) {
        if (e instanceof InvalidPasswordException) return "檔案有密碼保護";
        if (e instanceof ConversionException) return e.getMessage();
        if (e instanceof org.apache.poi.EncryptedDocumentException) return "檔案有密碼保護";
        String m = e == null ? null : e.getMessage();
        return "無法解析檔案" + (m == null || m.isBlank() ? "" : "：" + (m.length() > 120 ? m.substring(0, 120) + "…" : m));
    }

    static final class ConversionException extends IOException {
        ConversionException(String message) { super(message); }
    }

    // ------------------------------------------------------------------ PDF

    static String pdf(Path source) throws IOException {
        try (PDDocument doc = Loader.loadPDF(source.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            if (doc.isEncrypted() && !doc.getCurrentAccessPermission().canExtractContent())
                throw new ConversionException("檔案設定為不可擷取文字");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pages = doc.getNumberOfPages();
            Output out = new Output();
            long visible = 0;
            for (int i = 1; i <= pages && !out.full(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new ConversionException("轉換被中斷");
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc).strip();
                visible += text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
                out.append("## 第 " + i + " 頁\n\n" + (text.isEmpty() ? "（這一頁沒有文字層）" : text) + "\n\n");
            }
            // 掃描檔每頁只有零星字元（頁碼、浮水印）：給 agent 一份幾乎空白的 .md 反而誤導，改請它讀原檔。
            if (visible < Math.max(20, 10L * pages)) throw new ConversionException("沒有文字層（可能是掃描檔或圖片）");
            return out.text();
        }
    }

    // ------------------------------------------------------------------ Word

    static String docx(Path source) throws IOException {
        try (var in = Files.newInputStream(source); XWPFDocument doc = new XWPFDocument(in)) {
            Output out = new Output();
            for (IBodyElement element : doc.getBodyElements()) {
                if (out.full()) break;
                if (element instanceof XWPFParagraph p) {
                    String text = p.getText().strip();
                    if (text.isEmpty()) continue;
                    int level = headingLevel(doc, p);
                    if (level > 0) out.append("#".repeat(Math.min(level + 1, 6)) + " " + oneLine(text) + "\n\n");
                    else if (p.getNumID() != null) out.append("- " + oneLine(text) + "\n");
                    else out.append(text + "\n\n");
                } else if (element instanceof XWPFTable table) {
                    List<List<String>> rows = new ArrayList<>();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) cells.add(cell.getText());
                        rows.add(cells);
                    }
                    out.append("\n" + table(rows) + "\n");
                }
            }
            return out.text();
        }
    }

    private static int headingLevel(XWPFDocument doc, XWPFParagraph p) {
        String id = p.getStyleID();
        if (id == null) return 0;
        String name = id;
        XWPFStyles styles = doc.getStyles();
        if (styles != null && styles.getStyle(id) != null && styles.getStyle(id).getName() != null) name = styles.getStyle(id).getName();
        if (name.equalsIgnoreCase("title") || name.equals("標題")) return 1;
        for (String candidate : List.of(name, id)) {
            Matcher m = HEADING.matcher(candidate.replace("-", " ").trim());
            if (m.matches()) return Math.max(1, Integer.parseInt(m.group(1)));
        }
        return 0;
    }

    // ------------------------------------------------------------------ Excel

    static String sheets(Path source) throws IOException {
        try (Workbook book = WorkbookFactory.create(source.toFile(), null, true)) {
            DataFormatter format = new DataFormatter();
            Output out = new Output();
            for (Sheet sheet : book) {
                if (out.full()) break;
                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                for (Row row : sheet) {
                    if (rows.size() >= MAX_SHEET_ROWS) { truncated = true; break; }
                    List<String> cells = new ArrayList<>();
                    int last = Math.min(Math.max(row.getLastCellNum(), 0), MAX_SHEET_COLUMNS);
                    for (int c = 0; c < last; c++) cells.add(cellText(row.getCell(c), format));
                    while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) cells.remove(cells.size() - 1);
                    if (!cells.isEmpty()) rows.add(cells);
                }
                out.append("## 工作表：" + oneLine(sheet.getSheetName()) + "\n\n");
                out.append(rows.isEmpty() ? "（空白）\n\n" : table(rows) + "\n");
                if (truncated) out.append("（只轉出前 " + MAX_SHEET_ROWS + " 列，其餘請讀原檔）\n\n");
            }
            return out.text();
        }
    }

    /** 公式取上次計算結果（不在系統內重算，避免外部連結與巨集）。 */
    private static String cellText(Cell cell, DataFormatter format) {
        if (cell == null) return "";
        if (cell.getCellType() != CellType.FORMULA) return format.formatCellValue(cell);
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> format.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    // ------------------------------------------------------------------ PowerPoint

    static String pptx(Path source) throws IOException {
        try (var in = Files.newInputStream(source); XMLSlideShow show = new XMLSlideShow(in)) {
            Output out = new Output();
            int n = 0;
            for (XSLFSlide slide : show.getSlides()) {
                if (out.full()) break;
                n++;
                String title = slide.getTitle();
                out.append("## 投影片 " + n + (title == null || title.isBlank() ? "" : "：" + oneLine(title)) + "\n\n");
                shapes(slide.getShapes(), out);
                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder text = new StringBuilder();
                    for (List<XSLFTextParagraph> paragraphs : notes.getTextParagraphs())
                        for (XSLFTextParagraph p : paragraphs) if (!p.getText().isBlank()) text.append(p.getText().strip()).append('\n');
                    if (!text.isEmpty()) out.append("備註：\n" + text + "\n");
                }
            }
            return out.text();
        }
    }

    private static void shapes(List<XSLFShape> shapes, Output out) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFGroupShape group) shapes(group.getShapes(), out);
            else if (shape instanceof XSLFTable table) {
                List<List<String>> rows = new ArrayList<>();
                for (XSLFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XSLFTableCell cell : row.getCells()) cells.add(cell.getText());
                    rows.add(cells);
                }
                out.append(table(rows) + "\n");
            } else if (shape instanceof XSLFTextShape text && !text.getText().isBlank()) {
                out.append(text.getText().strip() + "\n\n");
            }
        }
    }

    // ------------------------------------------------------------------ 共用

    /** 第一列當表頭；欄數補齊；格內換行與 | 跳脫，避免破壞 Markdown 表格。 */
    static String table(List<List<String>> rows) {
        if (rows.isEmpty()) return "";
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        if (width == 0) return "";
        StringBuilder b = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            b.append('|');
            for (int c = 0; c < width; c++) {
                String v = c < rows.get(r).size() ? rows.get(r).get(c) : "";
                b.append(' ').append(oneLine(v).replace("|", "\\|")).append(" |");
            }
            b.append('\n');
            if (r == 0) b.append('|').append(" --- |".repeat(width)).append('\n');
        }
        return b.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s*[\\r\\n]+\\s*", " ").strip();
    }

    static String extension(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 累積輸出並在超過上限時截斷，避免巨大試算表把工作目錄與 agent 上下文塞滿。 */
    private static final class Output {
        private final StringBuilder text = new StringBuilder();
        private boolean full;

        void append(String s) {
            if (full) return;
            if (text.length() + s.length() > MAX_CHARS) {
                text.append(s, 0, Math.max(0, MAX_CHARS - text.length()));
                text.append("\n\n（以下省略：超過轉換上限 ").append(MAX_CHARS).append(" 字元，其餘請讀原檔）\n");
                full = true;
            } else text.append(s);
        }

        boolean full() { return full; }
        String text() { return text.toString(); }
    }
}
