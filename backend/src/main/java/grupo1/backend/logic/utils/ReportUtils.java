package grupo1.backend.logic.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReportUtils {

    public record Table(String title, List<String> headers, List<Integer> weights, List<List<Object>> rows) {
    }

    public record Series(String name, String color, List<Double> values) {
    }

    public record Chart(String title, List<String> labels, List<Series> series) {
    }

    public record Report(String title, List<String> details, List<Table> tables, Chart chart) {
    }

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private static final double PAGE_WIDTH = 842;
    private static final double PAGE_HEIGHT = 595;
    private static final double MARGIN = 36;
    private static final double ROW_HEIGHT = 16;
    private static final double CELL_PADDING = 4;
    private static final double BODY_SIZE = 8.5;
    private static final double CHART_HEIGHT = 190;
    private static final int SHEET_NAME_LENGTH = 28;
    private static final int[] HELVETICA_WIDTHS = {
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584
    };

    private ReportUtils() {
    }

    public static byte[] toCsv(Report report) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvRow(csv, List.of(report.title()));
        report.details().forEach(detail -> appendCsvRow(csv, List.of(detail)));
        allTables(report).forEach(table -> {
            csv.append("\r\n");
            appendCsvRow(csv, List.of(table.title()));
            appendCsvRow(csv, new ArrayList<>(table.headers()));
            table.rows().forEach(row -> appendCsvRow(csv, row));
        });
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] toXlsx(Report report) {
        List<Table> tables = allTables(report);
        List<String> sheetNames = sheetNames(tables);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeEntry(zip, "[Content_Types].xml", contentTypes(tables.size()));
            writeEntry(zip, "_rels/.rels", rootRelations());
            writeEntry(zip, "xl/workbook.xml", workbook(sheetNames));
            writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelations(tables.size()));
            writeEntry(zip, "xl/styles.xml", styles());
            for (int index = 0; index < tables.size(); index++) {
                List<String> intro = index == 0
                        ? Stream.concat(Stream.of(report.title()), report.details().stream()).toList()
                        : List.of();
                writeEntry(zip, "xl/worksheets/sheet%d.xml".formatted(index + 1), worksheet(intro, tables.get(index)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static byte[] toPdf(Report report) {
        PdfCanvas canvas = new PdfCanvas();
        canvas.text(MARGIN, canvas.y, 18, true, report.title(), "#0f172a");
        canvas.y -= 18;
        for (String detail : report.details()) {
            canvas.text(MARGIN, canvas.y, 9.5, false, detail, "#475569");
            canvas.y -= 13;
        }
        canvas.y -= 8;
        canvas.line(MARGIN, canvas.y, PAGE_WIDTH - MARGIN, canvas.y, "#cbd5e1", 0.8);
        canvas.y -= 20;

        List<Table> tables = report.tables();
        if (!tables.isEmpty()) {
            drawTable(canvas, tables.getFirst());
        }
        if (report.chart() != null) {
            drawChart(canvas, report.chart());
        }
        tables.stream().skip(1).forEach(table -> drawTable(canvas, table));
        return canvas.toBytes();
    }

    private static List<Table> allTables(Report report) {
        List<Table> tables = new ArrayList<>(report.tables());
        if (report.chart() != null) {
            tables.add(Math.min(1, tables.size()), chartTable(report.chart()));
        }
        return tables;
    }

    private static Table chartTable(Chart chart) {
        List<String> headers = Stream.concat(
                Stream.of("Hora"),
                chart.series().stream().map(series -> series.name() + " (%)")
        ).toList();
        List<List<Object>> rows = IntStream.range(0, chart.labels().size())
                .mapToObj(index -> Stream.concat(
                        Stream.<Object>of(chart.labels().get(index)),
                        chart.series().stream().map(series -> (Object) series.values().get(index))
                ).toList())
                .toList();
        return new Table(chart.title(), headers, null, rows);
    }

    private static void appendCsvRow(StringBuilder csv, List<?> values) {
        csv.append(values.stream()
                        .map(ReportUtils::cellText)
                        .map(ReportUtils::csvEscape)
                        .collect(Collectors.joining(",")))
                .append("\r\n");
    }

    private static String csvEscape(String value) {
        boolean quoted = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        return quoted ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String cellText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double number) {
            return number % 1 == 0 ? String.valueOf(number.longValue()) : String.format(Locale.US, "%.1f", number);
        }
        return value.toString();
    }

    private static List<String> sheetNames(List<Table> tables) {
        Set<String> used = new HashSet<>();
        return tables.stream().map(table -> {
            String base = table.title().replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
            if (base.length() > SHEET_NAME_LENGTH) {
                int space = base.lastIndexOf(' ', SHEET_NAME_LENGTH);
                base = base.substring(0, space > 0 ? space : SHEET_NAME_LENGTH);
            }
            base = base.isEmpty() ? "Hoja" : base;
            String name = base;
            int suffix = 2;
            while (!used.add(name.toLowerCase(Locale.ROOT))) {
                name = base + " " + suffix++;
            }
            return name;
        }).toList();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes(int sheets) {
        String overrides = IntStream.rangeClosed(1, sheets)
                .mapToObj(index -> "<Override PartName=\"/xl/worksheets/sheet%d.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>".formatted(index))
                .collect(Collectors.joining());
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>\
                %s</Types>""".formatted(overrides);
    }

    private static String rootRelations() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                </Relationships>""";
    }

    private static String workbook(List<String> sheetNames) {
        String sheets = IntStream.range(0, sheetNames.size())
                .mapToObj(index -> "<sheet name=\"%s\" sheetId=\"%d\" r:id=\"rId%d\"/>"
                        .formatted(xmlEscape(sheetNames.get(index)), index + 1, index + 1))
                .collect(Collectors.joining());
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                <sheets>%s</sheets></workbook>""".formatted(sheets);
    }

    private static String workbookRelations(int sheets) {
        String relations = IntStream.rangeClosed(1, sheets)
                .mapToObj(index -> "<Relationship Id=\"rId%d\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet%d.xml\"/>".formatted(index, index))
                .collect(Collectors.joining());
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                %s<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\
                </Relationships>""".formatted(relations, sheets + 1);
    }

    private static String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                <fonts count="3"><font><sz val="11"/><name val="Calibri"/></font>\
                <font><b/><sz val="11"/><name val="Calibri"/></font>\
                <font><b/><sz val="14"/><name val="Calibri"/></font></fonts>\
                <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>\
                <fill><patternFill patternType="solid"><fgColor rgb="FFE2E8F0"/><bgColor indexed="64"/></patternFill></fill></fills>\
                <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
                <cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
                <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>\
                <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>\
                <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>\
                <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>\
                </styleSheet>""";
    }

    private static String worksheet(List<String> intro, Table table) {
        List<String> rows = new ArrayList<>();
        int rowNumber = 1;
        for (int index = 0; index < intro.size(); index++) {
            rows.add(row(rowNumber++, List.of(intro.get(index)), index == 0 ? 2 : 0));
        }
        if (!intro.isEmpty()) {
            rowNumber++;
        }
        rows.add(row(rowNumber++, List.of(table.title()), 3));
        rows.add(row(rowNumber++, new ArrayList<>(table.headers()), 1));
        for (List<Object> values : table.rows()) {
            rows.add(row(rowNumber++, values, 0));
        }
        String columns = IntStream.range(0, table.headers().size())
                .mapToObj(index -> "<col min=\"%d\" max=\"%d\" width=\"%d\" customWidth=\"1\"/>"
                        .formatted(index + 1, index + 1, columnWidth(table, index)))
                .collect(Collectors.joining());
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                <cols>%s</cols><sheetData>%s</sheetData></worksheet>""".formatted(columns, String.join("", rows));
    }

    private static int columnWidth(Table table, int column) {
        int longest = Stream.concat(
                        Stream.of(table.headers().get(column)),
                        table.rows().stream().map(row -> column < row.size() ? cellText(row.get(column)) : ""))
                .mapToInt(String::length)
                .max()
                .orElse(10);
        return Math.max(10, Math.min(80, longest + 2));
    }

    private static String row(int number, List<?> values, int style) {
        String cells = IntStream.range(0, values.size())
                .mapToObj(index -> cell(columnName(index) + number, values.get(index), style))
                .collect(Collectors.joining());
        return "<row r=\"%d\">%s</row>".formatted(number, cells);
    }

    private static String cell(String reference, Object value, int style) {
        String styleAttribute = style == 0 ? "" : " s=\"%d\"".formatted(style);
        if (value == null) {
            return "<c r=\"%s\"%s/>".formatted(reference, styleAttribute);
        }
        if (value instanceof Number number) {
            return "<c r=\"%s\"%s><v>%s</v></c>".formatted(reference, styleAttribute, numberText(number));
        }
        return "<c r=\"%s\"%s t=\"inlineStr\"><is><t xml:space=\"preserve\">%s</t></is></c>"
                .formatted(reference, styleAttribute, xmlEscape(value.toString()));
    }

    private static String numberText(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            return Double.isFinite(value) ? String.format(Locale.US, "%s", value) : "0";
        }
        return number.toString();
    }

    private static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        int current = index + 1;
        while (current > 0) {
            int remainder = (current - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            current = (current - 1) / 26;
        }
        return name.toString();
    }

    private static String xmlEscape(String value) {
        return value.codePoints()
                .filter(code -> code == 0x9 || code == 0xA || code == 0xD || (code >= 0x20 && code <= 0xD7FF)
                        || (code >= 0xE000 && code <= 0xFFFD) || code >= 0x10000)
                .mapToObj(code -> switch (code) {
                    case '&' -> "&amp;";
                    case '<' -> "&lt;";
                    case '>' -> "&gt;";
                    case '"' -> "&quot;";
                    default -> Character.toString(code);
                })
                .collect(Collectors.joining());
    }

    private static void drawTable(PdfCanvas canvas, Table table) {
        int columns = table.headers().size();
        List<Integer> weights = table.weights() != null && table.weights().size() == columns
                ? table.weights()
                : IntStream.range(0, columns).mapToObj(index -> 1).toList();
        double totalWeight = weights.stream().mapToInt(Integer::intValue).sum();
        double width = PAGE_WIDTH - 2 * MARGIN;
        double[] widths = weights.stream().mapToDouble(weight -> width * weight / totalWeight).toArray();

        canvas.ensureSpace(ROW_HEIGHT * 3 + 20);
        canvas.text(MARGIN, canvas.y, 12, true, table.title(), "#0f172a");
        canvas.y -= 12;
        drawRow(canvas, table.headers().stream().map(header -> (Object) header).toList(), widths, true, false);
        if (table.rows().isEmpty()) {
            drawRow(canvas, List.of("Sin datos"), new double[]{width}, false, false);
        }
        for (int index = 0; index < table.rows().size(); index++) {
            if (canvas.y - ROW_HEIGHT < MARGIN + 14) {
                canvas.newPage();
                drawRow(canvas, table.headers().stream().map(header -> (Object) header).toList(), widths, true, false);
            }
            drawRow(canvas, table.rows().get(index), widths, false, index % 2 == 1);
        }
        canvas.y -= 22;
    }

    private static void drawRow(PdfCanvas canvas, List<Object> values, double[] widths, boolean header, boolean shaded) {
        double top = canvas.y;
        double bottom = top - ROW_HEIGHT;
        double tableWidth = IntStream.range(0, widths.length).mapToDouble(index -> widths[index]).sum();
        if (header) {
            canvas.rect(MARGIN, bottom, tableWidth, ROW_HEIGHT, "#e2e8f0");
        } else if (shaded) {
            canvas.rect(MARGIN, bottom, tableWidth, ROW_HEIGHT, "#f8fafc");
        }
        double x = MARGIN;
        for (int index = 0; index < widths.length; index++) {
            String value = index < values.size() ? cellText(values.get(index)) : "";
            String fitted = fit(value, widths[index] - 2 * CELL_PADDING, BODY_SIZE, header);
            canvas.text(x + CELL_PADDING, bottom + 5, BODY_SIZE, header, fitted, header ? "#0f172a" : "#1e293b");
            x += widths[index];
        }
        canvas.line(MARGIN, bottom, MARGIN + tableWidth, bottom, "#e2e8f0", 0.5);
        canvas.y = bottom;
    }

    private static void drawChart(PdfCanvas canvas, Chart chart) {
        canvas.ensureSpace(CHART_HEIGHT + 50);
        canvas.text(MARGIN, canvas.y, 12, true, chart.title(), "#0f172a");
        double legendX = PAGE_WIDTH - MARGIN;
        for (int index = chart.series().size() - 1; index >= 0; index--) {
            Series series = chart.series().get(index);
            double labelWidth = textWidth(series.name(), 9, false);
            legendX -= labelWidth;
            canvas.text(legendX, canvas.y, 9, false, series.name(), "#334155");
            legendX -= 14;
            canvas.rect(legendX, canvas.y - 1, 9, 9, series.color());
            legendX -= 14;
        }
        canvas.y -= 18;

        double left = MARGIN + 30;
        double right = PAGE_WIDTH - MARGIN;
        double top = canvas.y;
        double bottom = top - CHART_HEIGHT;
        for (int level = 0; level <= 100; level += 25) {
            double y = bottom + (top - bottom) * level / 100.0;
            canvas.line(left, y, right, y, "#e2e8f0", 0.6);
            canvas.text(MARGIN, y - 3, 8, false, level + "%", "#64748b");
        }
        int points = chart.labels().size();
        double step = points > 1 ? (right - left) / (points - 1) : 0;
        for (Series series : chart.series()) {
            List<double[]> segment = new ArrayList<>();
            for (int index = 0; index <= points; index++) {
                Double value = index < points ? series.values().get(index) : null;
                if (value == null) {
                    canvas.polyline(segment, series.color(), 1.8);
                    segment = new ArrayList<>();
                    continue;
                }
                double clamped = Math.max(0, Math.min(100, value));
                segment.add(new double[]{left + index * step, bottom + (top - bottom) * clamped / 100.0});
            }
        }
        List<Integer> labelIndexes = points == 0 ? List.of() : Stream.of(0, (points - 1) / 2, points - 1).distinct().toList();
        for (int index : labelIndexes) {
            String label = chart.labels().get(index);
            double x = left + index * step - textWidth(label, 8, false) / 2;
            canvas.text(x, bottom - 12, 8, false, label, "#64748b");
        }
        canvas.y = bottom - 36;
    }

    private static String fit(String value, double maxWidth, double size, boolean bold) {
        if (textWidth(value, size, bold) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        int end = value.length();
        while (end > 0 && textWidth(value.substring(0, end) + ellipsis, size, bold) > maxWidth) {
            end--;
        }
        return end == 0 ? "" : value.substring(0, end) + ellipsis;
    }

    private static double textWidth(String value, double size, boolean bold) {
        double units = value.chars()
                .mapToDouble(code -> code >= 32 && code <= 126 ? HELVETICA_WIDTHS[code - 32] : 556)
                .sum();
        return units * size / 1000.0 * (bold ? 1.06 : 1.0);
    }

    private static String pdfColor(String hex, String operator) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return String.format(Locale.US, "%.3f %.3f %.3f %s",
                (rgb >> 16 & 0xFF) / 255.0, (rgb >> 8 & 0xFF) / 255.0, (rgb & 0xFF) / 255.0, operator);
    }

    private static String pdfNumber(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String pdfText(String value) {
        String normalized = value.replace('↑', '+').replace('↓', '-').replace('▾', ' ');
        CharsetEncoder encoder = WIN_ANSI.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(new byte[]{'?'});
        try {
            ByteBuffer bytes = encoder.encode(CharBuffer.wrap(normalized));
            StringBuilder hex = new StringBuilder("<");
            while (bytes.hasRemaining()) {
                hex.append("%02X".formatted(bytes.get() & 0xFF));
            }
            return hex.append(">").toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class PdfCanvas {

        private final List<StringBuilder> pages = new ArrayList<>();
        private StringBuilder current;
        private double y;

        private PdfCanvas() {
            newPage();
        }

        private void newPage() {
            current = new StringBuilder();
            pages.add(current);
            y = PAGE_HEIGHT - MARGIN - 10;
        }

        private void ensureSpace(double height) {
            if (y - height < MARGIN + 14) {
                newPage();
            }
        }

        private void text(double x, double baseline, double size, boolean bold, String value, String color) {
            if (value == null || value.isEmpty()) {
                return;
            }
            current.append("BT ").append(pdfColor(color, "rg"))
                    .append(" /").append(bold ? "F2" : "F1").append(' ').append(pdfNumber(size)).append(" Tf ")
                    .append(pdfNumber(x)).append(' ').append(pdfNumber(baseline)).append(" Td ")
                    .append(pdfText(value)).append(" Tj ET\n");
        }

        private void rect(double x, double bottom, double width, double height, String color) {
            current.append(pdfColor(color, "rg")).append(' ')
                    .append(pdfNumber(x)).append(' ').append(pdfNumber(bottom)).append(' ')
                    .append(pdfNumber(width)).append(' ').append(pdfNumber(height)).append(" re f\n");
        }

        private void line(double x1, double y1, double x2, double y2, String color, double width) {
            current.append(pdfColor(color, "RG")).append(' ').append(pdfNumber(width)).append(" w ")
                    .append(pdfNumber(x1)).append(' ').append(pdfNumber(y1)).append(" m ")
                    .append(pdfNumber(x2)).append(' ').append(pdfNumber(y2)).append(" l S\n");
        }

        private void polyline(List<double[]> points, String color, double width) {
            if (points.isEmpty()) {
                return;
            }
            if (points.size() == 1) {
                double[] point = points.getFirst();
                rect(point[0] - 2.5, point[1] - 2.5, 5, 5, color);
                return;
            }
            current.append(pdfColor(color, "RG")).append(' ').append(pdfNumber(width)).append(" w 1 j ");
            for (int index = 0; index < points.size(); index++) {
                double[] point = points.get(index);
                current.append(pdfNumber(point[0])).append(' ').append(pdfNumber(point[1]))
                        .append(index == 0 ? " m " : " l ");
            }
            current.append("S\n");
        }

        private byte[] toBytes() {
            int total = pages.size();
            for (int index = 0; index < total; index++) {
                current = pages.get(index);
                String footer = "Página %d de %d".formatted(index + 1, total);
                text(PAGE_WIDTH - MARGIN - textWidth(footer, 8, false), MARGIN - 14, 8, false, footer, "#94a3b8");
                text(MARGIN, MARGIN - 14, 8, false, "DBWatch · Monitoreo de rendimiento", "#94a3b8");
            }

            List<String> objects = new ArrayList<>();
            objects.add("<< /Type /Catalog /Pages 2 0 R >>");
            String kids = IntStream.range(0, total)
                    .mapToObj(index -> "%d 0 R".formatted(5 + index * 2))
                    .collect(Collectors.joining(" "));
            objects.add("<< /Type /Pages /Kids [%s] /Count %d >>".formatted(kids, total));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
            for (int index = 0; index < total; index++) {
                String content = pages.get(index).toString();
                objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %s %s] "
                        + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents %d 0 R >>")
                        .formatted(pdfNumber(PAGE_WIDTH), pdfNumber(PAGE_HEIGHT), 6 + index * 2));
                objects.add("<< /Length %d >>\nstream\n%sendstream".formatted(
                        content.getBytes(StandardCharsets.US_ASCII).length, content));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            write(out, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n");
            for (int index = 0; index < objects.size(); index++) {
                offsets.add(out.size());
                write(out, "%d 0 obj\n%s\nendobj\n".formatted(index + 1, objects.get(index)));
            }
            int xref = out.size();
            StringBuilder table = new StringBuilder("xref\n0 %d\n0000000000 65535 f \n".formatted(objects.size() + 1));
            offsets.forEach(offset -> table.append("%010d 00000 n \n".formatted(offset)));
            table.append("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n".formatted(objects.size() + 1, xref));
            write(out, table.toString());
            return out.toByteArray();
        }

        private static void write(ByteArrayOutputStream out, String value) {
            out.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
        }
    }
}