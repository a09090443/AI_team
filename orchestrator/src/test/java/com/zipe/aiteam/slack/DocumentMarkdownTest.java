package com.zipe.aiteam.slack;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentMarkdownTest {
    @TempDir Path dir;

    private String convert(Path source) throws Exception {
        Path md = dir.resolve(source.getFileName() + ".md");
        String reason = DocumentMarkdown.convert(source, md, source.getFileName().toString());
        assertThat(reason).as("轉換失敗原因").isNull();
        return Files.readString(md);
    }

    @Test void pdfTextIsExtractedPerPage() throws Exception {
        Path pdf = dir.resolve("api.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (String line : List.of("GET /orders/{id} returns the order", "POST /orders creates an order")) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(line);
                    content.endText();
                }
            }
            doc.save(pdf.toFile());
        }
        String md = convert(pdf);
        assertThat(md).contains("以原檔為準", "## 第 1 頁", "GET /orders/{id}", "## 第 2 頁", "POST /orders");
        Path fallback = dir.resolve("fallback.md");
        assertThat(DocumentMarkdown.convert(pdf, fallback, "api.pdf", dir.resolve("missing-pdf2md").toString())).isNull();
        assertThat(Files.readString(fallback)).isEqualTo(md);
    }

    @Test void inspectorKeepsTablesAndWarnsAboutMissingPagesAndEncoding() throws Exception {
        byte[] result = """
                {"pdf_type":"mixed","page_count":2,"has_text":true,"pages_needing_ocr":[2],
                 "has_encoding_issues":true,"markdown":"# 訂單 API\\n\\n| 欄位 | 必填 |\\n| --- | --- |\\n| id | 是 |"}
                """.getBytes(StandardCharsets.UTF_8);
        assertThat(DocumentMarkdown.inspectorMarkdown(result, 2))
                .contains("第 2 頁", "編碼問題", "# 訂單 API", "| id | 是 |");
        assertThat(DocumentMarkdown.inspectorMarkdown(result, 3)).isNull();
    }

    @Test void inspectorRejectsScannedPdfEvenIfItExtractedPageNumbers() throws Exception {
        byte[] result = """
                {"pdf_type":"scanned","page_count":2,"has_text":true,"pages_needing_ocr":[1,2],
                 "has_encoding_issues":false,"markdown":"1\\n2"}
                """.getBytes(StandardCharsets.UTF_8);
        assertThat(DocumentMarkdown.inspectorMarkdown(result, 2)).isNull();
    }

    @Test void pdfWithoutTextLayerProducesNoFile() throws Exception {
        Path pdf = dir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) { doc.addPage(new PDPage()); doc.save(pdf.toFile()); }
        Path md = dir.resolve("scan.pdf.md");
        assertThat(DocumentMarkdown.convert(pdf, md, "scan.pdf")).contains("沒有文字層");
        assertThat(md).doesNotExist();
    }

    @Test void brokenFileReportsReasonWithoutOutput() throws Exception {
        Path bad = dir.resolve("bad.docx");
        Files.writeString(bad, "not a zip");
        Path md = dir.resolve("bad.docx.md");
        assertThat(DocumentMarkdown.convert(bad, md, "bad.docx")).startsWith("無法解析檔案");
        assertThat(md).doesNotExist();
        try (var list = Files.list(dir)) { assertThat(list.map(p -> p.getFileName().toString())).containsExactly("bad.docx"); }
    }

    @Test void docxHeadingsListsAndTables() throws Exception {
        Path docx = dir.resolve("spec.docx");
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(docx)) {
            var heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("訂單 API");
            doc.createParagraph().createRun().setText("回傳 JSON。");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("欄位");
            table.getRow(0).getCell(1).setText("說明");
            table.getRow(1).getCell(0).setText("id");
            table.getRow(1).getCell(1).setText("訂單|編號");
            doc.write(out);
        }
        String md = convert(docx);
        assertThat(md).contains("## 訂單 API", "回傳 JSON。", "| 欄位 | 說明 |", "| --- | --- |", "| id | 訂單\\|編號 |");
    }

    @Test void spreadsheetsBecomeTablesPerSheet() throws Exception {
        for (String name : List.of("data.xlsx", "data.xls")) {
            Path file = dir.resolve(name);
            try (Workbook book = name.endsWith("x") ? new XSSFWorkbook() : new HSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
                Sheet sheet = book.createSheet("錯誤碼");
                Row head = sheet.createRow(0);
                head.createCell(0).setCellValue("code");
                head.createCell(1).setCellValue("count");
                Row row = sheet.createRow(1);
                row.createCell(0).setCellValue("E01");
                row.createCell(1).setCellValue(3);
                Row sum = sheet.createRow(2);
                sum.createCell(0).setCellValue("total");
                sum.createCell(1).setCellFormula("B2*2");
                book.getCreationHelper().createFormulaEvaluator().evaluateAll();
                book.write(out);
            }
            assertThat(convert(file)).as(name).contains("## 工作表：錯誤碼", "| code | count |", "| E01 | 3 |", "| total | 6 |");
        }
    }

    @Test void pptxSlidesWithTitlesAndText() throws Exception {
        Path pptx = dir.resolve("deck.pptx");
        try (XMLSlideShow show = new XMLSlideShow(); OutputStream out = Files.newOutputStream(pptx)) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("Mock 情境：成功、查無資料");
            show.write(out);
        }
        assertThat(convert(pptx)).contains("## 投影片 1", "Mock 情境：成功、查無資料");
    }

    @Test void onlyKnownFormatsAreSupported() {
        assertThat(DocumentMarkdown.supports("F1-API.PDF")).isTrue();
        assertThat(DocumentMarkdown.supports("a.xls")).isTrue();
        assertThat(DocumentMarkdown.supports("a.doc")).isFalse();
        assertThat(DocumentMarkdown.supports("a.txt")).isFalse();
        assertThat(DocumentMarkdown.supports("noext")).isFalse();
    }

    @Test void tableEscapesAndPadsCells() {
        assertThat(DocumentMarkdown.table(List.of(List.of("a", "b"), List.of("x\ny")))).isEqualTo("| a | b |\n| --- | --- |\n| x y |  |\n");
    }
}
