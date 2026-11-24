package a5er;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * A5:SQL Mk-2 の ER 図ファイル（.a5er）用ツール。
 *
 * <ul>
 *   <li>テーブルの自動整列（compact / layered / grid）。線が重ならないようにリレーション線の曲がる位置（Bar1〜3）も決める</li>
 *   <li>論理名（テーブル物理名＋カラム物理名の組み合わせ）の CSV 書き出し・読み込み</li>
 *   <li>リレーションの CSV 書き出し・読み込み</li>
 *   <li>一括（論理名の反映 → リレーションの追加 → 整列）</li>
 * </ul>
 *
 * 引数なしで起動すると画面、引数ありならコマンドとして動く。元のファイルは書き換えず、別名で出力する。
 * 書き換えるのは必要な行だけで、それ以外は文字コード・改行コードを含めて元のまま残す。
 */
public final class A5erTool {

    private A5erTool() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            SwingUtilities.invokeLater(MainWindow::open);
            return;
        }
        System.exit(Cli.run(args));
    }

    // =====================================================================
    // 共通
    // =====================================================================

    static final Set<String> TABLE_SECTIONS = Set.of("Entity", "View");
    static final String DEFAULT_PAGE = "MAIN";
    static final Charset MS932 = Charset.forName("MS932");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 利用者に見せるエラー。 */
    static final class ToolException extends Exception {
        ToolException(String message) {
            super(message);
        }
    }

    /** Python の round と同じ（偶数丸め）。 */
    static int rnd(double v) {
        return (int) Math.rint(v);
    }

    static boolean startsWithBom(byte[] data) {
        return data.length >= 3 && data[0] == UTF8_BOM[0] && data[1] == UTF8_BOM[1] && data[2] == UTF8_BOM[2];
    }

    static String decodeStrict(byte[] data, Charset cs) throws CharacterCodingException {
        return cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
    }

    // =====================================================================
    // .a5er の読み書き（文字コード・BOM・行と改行コード）
    // =====================================================================

    static final class TextFile {
        final List<String> lines;
        final Charset charset;
        final boolean bom;

        TextFile(List<String> lines, Charset charset, boolean bom) {
            this.lines = lines;
            this.charset = charset;
            this.bom = bom;
        }

        static TextFile decode(byte[] data) {
            boolean bom = startsWithBom(data);
            byte[] body = bom ? Arrays.copyOfRange(data, 3, data.length) : data;
            String text;
            Charset cs;
            if (bom) {
                text = new String(body, StandardCharsets.UTF_8);
                cs = StandardCharsets.UTF_8;
            } else {
                try {
                    text = decodeStrict(body, StandardCharsets.UTF_8);
                    cs = StandardCharsets.UTF_8;
                } catch (CharacterCodingException e) {
                    text = new String(body, MS932);
                    cs = MS932;
                }
            }
            return new TextFile(splitLines(text), cs, bom);
        }

        byte[] encode(List<String> out) {
            byte[] body = String.join("", out).getBytes(charset);
            if (!bom) {
                return body;
            }
            byte[] all = new byte[body.length + 3];
            System.arraycopy(UTF8_BOM, 0, all, 0, 3);
            System.arraycopy(body, 0, all, 3, body.length);
            return all;
        }

        /** 1 行目の改行コード（無ければ "\n"）。 */
        String eol() {
            if (!lines.isEmpty()) {
                String e = splitEol(lines.get(0))[1];
                if (!e.isEmpty()) {
                    return e;
                }
            }
            return "\n";
        }
    }

    /** "\n" で分け、改行コードを含めたまま行にする。 */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** [本文, 改行コード]。 */
    static String[] splitEol(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return new String[] {line.substring(0, end), line.substring(end)};
    }

    /** "キー=値" の値だけ差し替える（改行コードは保つ）。 */
    static String replaceValue(String line, String value) {
        String[] be = splitEol(line);
        String key = be[0].split("=", 2)[0];
        return key + "=" + value + be[1];
    }

    static String valueOf(String line) {
        String body = splitEol(line)[0];
        int eq = body.indexOf('=');
        return eq < 0 ? "" : body.substring(eq + 1);
    }

    private static final Pattern POSITION = Pattern.compile("^(\"(?:[^\"]|\"\")*\"|[^,]*),(.*)$", Pattern.DOTALL);

    /** Position="ページ",左,上[,...] を [ページの字句, 残り...] に分ける。分けられなければ null。 */
    static String[] parsePosition(String value) {
        Matcher m = POSITION.matcher(value);
        if (!m.matches()) {
            return null;
        }
        String[] rest = m.group(2).split(",", -1);
        String[] out = new String[rest.length + 1];
        out[0] = m.group(1);
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    static String unquote(String token) {
        String t = token.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).replace("\"\"", "\"");
        }
        return t;
    }

    static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static Integer toInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isSectionHeader(String stripped) {
        return stripped.startsWith("[") && stripped.endsWith("]");
    }

    // =====================================================================
    // CSV
    // =====================================================================

    static final class Csv {
        /** 読んだ 1 レコードと、その終わりの行番号。 */
        static final class Row {
            final int lineNo;
            final List<String> cells;

            Row(int lineNo, List<String> cells) {
                this.lineNo = lineNo;
                this.cells = cells;
            }
        }

        static List<Row> parse(String text) {
            List<Row> rows = new ArrayList<>();
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean inQuotes = false;
            boolean any = false;
            int line = 1;
            int i = 0;
            while (i < text.length()) {
                char ch = text.charAt(i);
                if (inQuotes) {
                    if (ch == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                            cell.append('"');
                            i += 2;
                            continue;
                        }
                        inQuotes = false;
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        cell.append(ch);
                    }
                    i++;
                    continue;
                }
                if (ch == '"' && cell.length() == 0) {
                    inQuotes = true;
                    any = true;
                } else if (ch == ',') {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    any = true;
                } else if (ch == '\r' || ch == '\n') {
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    if (any || cell.length() > 0) {
                        cells.add(cell.toString());
                    }
                    rows.add(new Row(line, cells));
                    cells = new ArrayList<>();
                    cell.setLength(0);
                    any = false;
                    line++;
                } else {
                    cell.append(ch);
                    any = true;
                }
                i++;
            }
            if (any || cell.length() > 0 || !cells.isEmpty()) {
                cells.add(cell.toString());
                rows.add(new Row(line, cells));
            }
            return rows;
        }

        /** 1 行分（.a5er の Field= の値など）を読む。 */
        static List<String> parseLine(String value) {
            List<Row> rows = parse(value);
            return rows.isEmpty() ? List.of() : rows.get(0).cells;
        }

        static String field(String s) {
            if (s.contains(",") || s.contains("\"") || s.contains("\r") || s.contains("\n")) {
                return quote(s);
            }
            return s;
        }

        /** BOM 付き UTF-8・改行 CRLF（Excel 向け）。 */
        static byte[] write(List<List<String>> rows) {
            StringBuilder sb = new StringBuilder();
            for (List<String> row : rows) {
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(field(row.get(i)));
                }
                sb.append("\r\n");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(UTF8_BOM);
            out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        }

        /** BOM 付き UTF-8 → UTF-8 → MS932 の順に試す。 */
        static String decode(byte[] data) throws ToolException {
            byte[] body = startsWithBom(data) ? Arrays.copyOfRange(data, 3, data.length) : data;
            try {
                return decodeStrict(body, StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                try {
                    return decodeStrict(data, MS932);
                } catch (CharacterCodingException e2) {
                    throw new ToolException("CSV の文字コードを読めません（UTF-8 か Shift_JIS で保存してください）。");
                }
            }
        }
    }

    // =====================================================================
    // 整列用の読み取り（テーブル・配置・リレーション、大きさの推定）
    // =====================================================================

    // 大きさの推定（A5:SQL Mk-2 の画面を測って決めた値。FontSize=6 のとき）
    static final double BASE_FONT_SIZE = 6;
    static final int WIDTH_KANJI = 22;
    static final int WIDTH_KANA = 16;
    static final int WIDTH_ASCII = 10;
    static final int WIDTH_FK = 40;
    static final int BOX_PADDING_X = 34;
    static final int MIN_WIDTH = 130;
    static final double ROW_HEIGHT = 25.3;
    static final int BOX_PADDING_Y = 2;
    static final int TEXT_HEIGHT = 26;
    static final int LABEL_HEIGHT = 60;

    /** 1 つのテーブルの、1 つのページ上の配置。 */
    static final class Placement {
        final Table table;
        final String page;
        final Integer posIdx;
        int w;
        int h;
        final int labelW;
        int x;
        int y;

        Placement(Table table, String page, Integer posIdx, int w, int h, int labelW) {
            this.table = table;
            this.page = page;
            this.posIdx = posIdx;
            this.w = w;
            this.h = h;
            this.labelW = labelW;
        }
    }

    static final class Table {
        String pname = "";
        String lname = "";
        String page = "";
        final List<String[]> fields = new ArrayList<>(); // [論理名, 物理名]
        Integer leftIdx;
        Integer topIdx;
        final List<Integer> posLines = new ArrayList<>();
        final List<Placement> placements = new ArrayList<>();

        String name() {
            return pname.isEmpty() ? lname : pname;
        }
    }

    static final class Relation {
        String parent = "";
        String child = "";
        String fields2 = "";
        final List<Object[]> posLines = new ArrayList<>(); // [行番号, 大文字のページ名]
        final Map<Integer, Integer> barIdx = new HashMap<>();
        final Map<String, int[]> routes = new LinkedHashMap<>(); // ページ → Bar×10（0〜10000）
    }

    static final class Document {
        final TextFile file;
        final List<Table> tables;
        final List<Relation> relations;
        final double fontSize;
        final String mainPage;
        final boolean logicalView;
        int unrouted;

        Document(TextFile file, List<Table> tables, List<Relation> relations, double fontSize, String mainPage,
                 boolean logicalView) {
            this.file = file;
            this.tables = tables;
            this.relations = relations;
            this.fontSize = fontSize;
            this.mainPage = mainPage;
            this.logicalView = logicalView;
        }

        List<String> lines() {
            return file.lines;
        }
    }

    static Document parseDocument(byte[] data) {
        TextFile file = TextFile.decode(data);
        List<String> lines = file.lines;
        List<Table> tables = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        double fontSize = BASE_FONT_SIZE;
        String mainPage = DEFAULT_PAGE;
        boolean logicalView = true;
        String section = null;
        Table table = null;
        Relation rel = null;

        for (int i = 0; i < lines.size(); i++) {
            String body = splitEol(lines.get(i))[0];
            String stripped = body.strip();
            if (isSectionHeader(stripped)) {
                section = stripped.substring(1, stripped.length() - 1);
                table = null;
                rel = null;
                if (TABLE_SECTIONS.contains(section)) {
                    table = new Table();
                    tables.add(table);
                } else if (section.equals("Relation")) {
                    rel = new Relation();
                    relations.add(rel);
                }
                continue;
            }
            if (!body.contains("=") || body.startsWith("#")) {
                continue;
            }
            String[] kv = body.split("=", 2);
            String key = kv[0].strip();
            String value = kv[1];
            if (table != null) {
                switch (key) {
                    case "PName": table.pname = value; break;
                    case "LName": table.lname = value; break;
                    case "Page": table.page = value; break;
                    case "Left": table.leftIdx = i; break;
                    case "Top": table.topIdx = i; break;
                    case "Position": table.posLines.add(i); break;
                    case "Field": {
                        List<String> cols = Csv.parseLine(value);
                        table.fields.add(new String[] {cols.isEmpty() ? "" : cols.get(0), cols.size() > 1 ? cols.get(1) : ""});
                        break;
                    }
                    default: break;
                }
            } else if (rel != null) {
                switch (key) {
                    case "Entity1": rel.parent = value; break;
                    case "Entity2": rel.child = value; break;
                    case "Fields2": rel.fields2 = value; break;
                    case "Position": {
                        String[] pos = parsePosition(value);
                        if (pos != null) {
                            String page = unquote(pos[0]);
                            rel.posLines.add(new Object[] {i, (page.isEmpty() ? DEFAULT_PAGE : page).toUpperCase(Locale.ROOT)});
                        }
                        break;
                    }
                    case "Bar1": case "Bar2": case "Bar3":
                        rel.barIdx.put(key.charAt(3) - '0', i);
                        break;
                    default: break;
                }
            } else if ("Manager".equals(section)) {
                if (key.equals("FontSize")) {
                    try {
                        double f = Double.parseDouble(value.strip());
                        fontSize = f == 0 ? BASE_FONT_SIZE : f;
                    } catch (NumberFormatException e) {
                        // そのまま
                    }
                } else if (key.equals("Page") && !value.isEmpty()) {
                    mainPage = value;
                } else if (key.equals("LogicalView")) {
                    logicalView = !value.strip().equals("0");
                }
            }
        }

        tables.removeIf(t -> t.name().isEmpty());
        relations.removeIf(r -> r.parent.isEmpty() || r.child.isEmpty());
        Map<String, Set<String>> fk = new HashMap<>();
        for (Relation r : relations) {
            Set<String> cols = fk.computeIfAbsent(r.child, k -> new HashSet<>());
            for (String c : r.fields2.split(",")) {
                if (!c.strip().isEmpty()) {
                    cols.add(c.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        double k = fontSize / BASE_FONT_SIZE;
        for (Table t : tables) {
            buildPlacements(t, lines, fk.getOrDefault(t.name(), Set.of()), k, logicalView);
        }
        return new Document(file, tables, relations, fontSize, mainPage, logicalView);
    }

    static void buildPlacements(Table t, List<String> lines, Set<String> fk, double k, boolean logicalView) {
        int[] est = estimateSize(t, fk, k, logicalView);
        String label = logicalView ? (t.lname.isEmpty() ? t.pname : t.lname) : (t.pname.isEmpty() ? t.lname : t.pname);
        int labelW = rnd(textWidth(label) * k);
        for (int idx : t.posLines) {
            String[] pos = parsePosition(valueOf(lines.get(idx)));
            if (pos == null) {
                continue;
            }
            Integer w = pos.length > 3 ? toInt(pos[3]) : null;
            Integer h = pos.length > 4 ? toInt(pos[4]) : null;
            String page = unquote(pos[0]);
            t.placements.add(new Placement(t, (page.isEmpty() ? DEFAULT_PAGE : page).toUpperCase(Locale.ROOT), idx,
                    w != null && w > 0 ? w : est[0], h != null && h > 0 ? h : est[1], labelW));
        }
        if (t.placements.isEmpty()) {
            String page = t.page.isEmpty() ? DEFAULT_PAGE : t.page;
            t.placements.add(new Placement(t, page.toUpperCase(Locale.ROOT), null, est[0], est[1], labelW));
        }
    }

    static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0xA4CF) || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE4F) || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6) || cp > 0xFFFF
                // 幅があいまいな文字（記号・ギリシャ文字・キリル文字など）も全角として数える
                || cp == 0xA7 || cp == 0xB0 || cp == 0xB1 || cp == 0xB4 || cp == 0xB6 || cp == 0xD7 || cp == 0xF7
                || (cp >= 0x0391 && cp <= 0x03C9) || (cp >= 0x0401 && cp <= 0x0451)
                || (cp >= 0x2010 && cp <= 0x203F) || (cp >= 0x2100 && cp <= 0x2BFF);
    }

    /** FontSize=6 のときの文字列の表示幅（実測の近似）。 */
    static int textWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x3040 && cp <= 0x30FF) {
                w += WIDTH_KANA;
            } else if (isWide(cp)) {
                w += WIDTH_KANJI;
            } else {
                w += WIDTH_ASCII;
            }
        }
        return w;
    }

    /** 表示される名前（論理名表示なら論理名、物理名表示なら物理名）から [幅, 高さ] を見積もる。 */
    static int[] estimateSize(Table t, Set<String> fk, double k, boolean logicalView) {
        int longest = 0;
        for (String[] f : t.fields) {
            String logical = f[0];
            String physical = f[1];
            String shown = logicalView ? (logical.isEmpty() ? physical : logical) : (physical.isEmpty() ? logical : physical);
            int width = textWidth(shown) + (fk.contains(physical.strip().toLowerCase(Locale.ROOT)) ? WIDTH_FK : 0);
            longest = Math.max(longest, width);
        }
        int w = rnd(Math.max(MIN_WIDTH, longest + BOX_PADDING_X) * k);
        int h = rnd(t.fields.size() * ROW_HEIGHT * k) + BOX_PADDING_Y;
        return new int[] {w, h};
    }

    // =====================================================================
    // 線の決まりと線の探索
    //
    // A5:SQL Mk-2 の線の決まり（実機で測定）:
    // - 向き: 高さの範囲が重なれば横向き、幅の範囲が重なれば縦向き。斜めなら中心どうしの上下差と左右差の大きいほう
    // - 縦向き: P の相手側の横の辺の x = P.左 + Bar1×P.幅
    //   → 中の横線 y = 上のテーブルの枠の下端 + Bar2×(下のテーブルの名前の文字の上端 − 上のテーブルの枠の下端)
    //   → C の相手側の横の辺の x = C.左 + Bar3×C.幅。上から入る線はテーブル名の帯の上（枠の上端 − 約 60）で止まる
    // - 横向き: P の相手側の縦の辺の y = P.上 + Bar1×P.高さ
    //   → 中の縦線 x = 左のテーブルの右辺 + Bar2×(右のテーブルの左辺 − 左のテーブルの右辺) → C の縦の辺の y = C.上 + Bar3×C.高さ
    // =====================================================================

    static final class Router {
        static final double CLEARANCE = 10;
        static final double PARALLEL_GAP = 15;
        static final double END_MIN = 30;
        static final double TERMINAL_ZONE = 30;
        static final double AMBIGUOUS = 40;
        static final double OVERLAP_MARGIN = 20;
        static final double BEND_COST = 50;
        static final double CROSS_COST = 400;
        static final double STRAIGHT_STEP = 20;
        static final int SELF_LOOP_RIGHT = 130;
        static final int SELF_LOOP_TOP = 60;
        static final double SEARCH_MARGIN = 3;

        static final double[] FRACTIONS_END = {0.5, 0.35, 0.65, 0.2, 0.8, 0.1, 0.9};
        static final double[] FRACTIONS_MID = {0.5, 0.35, 0.65, 0.2, 0.8};
        static final double[] FINE_END = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.05, 0.95, 0.25, 0.75};
        static final double[] FINE_MID = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};

        private Router() {
        }

        /** テーブルの枠と占有範囲（帯・自己参照の線を含む）。 */
        static final class Box {
            final double x;
            final double y;
            final double w;
            final double h;
            final double labelH;
            final double textH;
            final double labelW;
            final boolean selfLoop;

            Box(double x, double y, double w, double h, double labelH, double textH, double labelW, boolean selfLoop) {
                this.x = x;
                this.y = y;
                this.w = w;
                this.h = h;
                this.labelH = labelH;
                this.textH = textH;
                this.labelW = labelW;
                this.selfLoop = selfLoop;
            }

            double right() {
                return x + w;
            }

            double bottom() {
                return y + h;
            }

            /** 帯を含めた上端（上から入る線はここで止まる）。 */
            double top() {
                return y - labelH;
            }

            double[] occupied() {
                double r = selfLoop ? SELF_LOOP_RIGHT : 0;
                double t = selfLoop ? SELF_LOOP_TOP : 0;
                return new double[] {x, y - labelH - t, x + Math.max(w, labelW) + r, y + h};
            }
        }

        /** 線（水平・垂直の線分の並び）。 */
        static final class Line {
            final List<double[]> segments;
            final double[] start;
            final double[] end;

            Line(List<double[]> segments, double[] start, double[] end) {
                this.segments = segments;
                this.start = start;
                this.end = end;
            }
        }

        static final class Route {
            final double[] bars;
            final Line line;
            final double cost;

            Route(double[] bars, Line line, double cost) {
                this.bars = bars;
                this.line = line;
                this.cost = cost;
            }
        }

        static double[] gaps(Box p, Box c) {
            double gx = Math.max(c.x - p.right(), p.x - c.right());
            double gy = Math.max(c.y - c.textH - p.bottom(), p.y - p.textH - c.bottom());
            return new double[] {gx, gy};
        }

        /** 'v'（縦向き）/ 'h'（横向き）/ 0（枠が重なる・向きがあいまい）。 */
        static char direction(Box p, Box c) {
            double ox = Math.min(p.right(), c.right()) - Math.max(p.x, c.x);
            double oy = Math.min(p.bottom(), c.bottom()) - Math.max(p.y, c.y);
            double edge = OVERLAP_MARGIN;
            if (ox > edge && oy > edge) {
                return 0;
            }
            if ((-edge <= ox && ox <= edge) || (-edge <= oy && oy <= edge)) {
                return 0;
            }
            double[] g = gaps(p, c);
            if (oy > edge) {
                return g[0] > 0 ? 'h' : 0;
            }
            if (ox > edge) {
                return g[1] > 0 ? 'v' : 0;
            }
            double dx = Math.abs((p.x + p.w / 2) - (c.x + c.w / 2));
            double dy = Math.abs((p.y + p.h / 2) - (c.y + c.h / 2));
            if (Math.abs(dy - dx) < AMBIGUOUS) {
                return 0;
            }
            if (dy > dx) {
                return g[1] > 0 ? 'v' : 0;
            }
            return g[0] > 0 ? 'h' : 0;
        }

        static double[][] linePoints(Box p, Box c, double[] bars, char mode) {
            double b1 = bars[0];
            double b2 = bars[1];
            double b3 = bars[2];
            if (mode == 'v') {
                boolean pUpper = p.bottom() <= c.top();
                Box upper = pUpper ? p : c;
                Box lower = pUpper ? c : p;
                double py = pUpper ? p.bottom() : p.top();
                double cy = pUpper ? c.top() : c.bottom();
                double px = p.x + b1 * p.w;
                double cx = c.x + b3 * c.w;
                double my = upper.bottom() + b2 * (lower.y - lower.textH - upper.bottom());
                return new double[][] {{px, py}, {px, my}, {cx, my}, {cx, cy}};
            }
            boolean pLeft = p.right() <= c.x;
            Box left = pLeft ? p : c;
            Box right = pLeft ? c : p;
            double px = pLeft ? p.right() : p.x;
            double cx = pLeft ? c.x : c.right();
            double py = p.y + b1 * p.h;
            double cy = c.y + b3 * c.h;
            double mx = left.right() + b2 * (right.x - left.right());
            return new double[][] {{px, py}, {mx, py}, {mx, cy}, {cx, cy}};
        }

        static Line toLine(double[][] pts) {
            List<double[]> segs = new ArrayList<>();
            for (int i = 0; i + 1 < pts.length; i++) {
                double[] a = pts[i];
                double[] b = pts[i + 1];
                if (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) >= 1) { // 丸めでできる 1 未満の段差は線として扱わない
                    segs.add(new double[] {a[0], a[1], b[0], b[1]});
                }
            }
            return new Line(segs, pts[0], pts[pts.length - 1]);
        }

        static boolean segHitsRect(double[] s, double[] r, double pad) {
            double loX = Math.min(s[0], s[2]);
            double hiX = Math.max(s[0], s[2]);
            double loY = Math.min(s[1], s[3]);
            double hiY = Math.max(s[1], s[3]);
            return loX < r[2] + pad && hiX > r[0] - pad && loY < r[3] + pad && hiY > r[1] - pad;
        }

        private static boolean near(double x, double y, double[] q) {
            return Math.abs(x - q[0]) + Math.abs(y - q[1]) < TERMINAL_ZONE;
        }

        /** a と b の交差数。重なる（gap 未満に近づく）・端の近くで交わる場合は -1。 */
        static int compare(Line a, Line b, double gap) {
            int crosses = 0;
            for (double[] s : a.segments) {
                boolean sh = Math.abs(s[1] - s[3]) < 1e-6;
                for (double[] t : b.segments) {
                    boolean th = Math.abs(t[1] - t[3]) < 1e-6;
                    if (sh == th) {
                        if (sh) {
                            if (Math.abs(s[1] - t[1]) < gap
                                    && Math.max(Math.min(s[0], s[2]), Math.min(t[0], t[2]))
                                    < Math.min(Math.max(s[0], s[2]), Math.max(t[0], t[2])) + gap) {
                                return -1;
                            }
                        } else {
                            if (Math.abs(s[0] - t[0]) < gap
                                    && Math.max(Math.min(s[1], s[3]), Math.min(t[1], t[3]))
                                    < Math.min(Math.max(s[1], s[3]), Math.max(t[1], t[3])) + gap) {
                                return -1;
                            }
                        }
                        continue;
                    }
                    double[] h = sh ? s : t;
                    double[] v = sh ? t : s;
                    double x = v[0];
                    double y = h[1];
                    if (Math.min(h[0], h[2]) <= x && x <= Math.max(h[0], h[2])
                            && Math.min(v[1], v[3]) <= y && y <= Math.max(v[1], v[3])) {
                        if (near(x, y, a.start) || near(x, y, a.end) || near(x, y, b.start) || near(x, y, b.end)) {
                            return -1;
                        }
                        crosses++;
                    }
                }
            }
            return crosses;
        }

        static int compare(Line a, Line b) {
            return compare(a, b, PARALLEL_GAP);
        }

        static List<double[]> candidates(Box p, Box c, char mode, boolean fine) {
            List<double[]> out = new ArrayList<>();
            double lo;
            double hi;
            double sp0;
            double sp1;
            double sc0;
            double sc1;
            if (mode == 'v') {
                lo = Math.max(p.x, c.x) + 10;
                hi = Math.min(p.right(), c.right()) - 10;
                sp0 = p.x;
                sp1 = p.w;
                sc0 = c.x;
                sc1 = c.w;
            } else {
                lo = Math.max(p.y, c.y) + 10;
                hi = Math.min(p.bottom(), c.bottom()) - 10;
                sp0 = p.y;
                sp1 = p.h;
                sc0 = c.y;
                sc1 = c.h;
            }
            if (hi >= lo) {
                double mid = (lo + hi) / 2;
                for (int k = 0; ; k++) {
                    List<Double> vals = new ArrayList<>();
                    if (k == 0) {
                        vals.add(mid);
                    } else {
                        vals.add(mid - k * STRAIGHT_STEP);
                        vals.add(mid + k * STRAIGHT_STEP);
                    }
                    vals.removeIf(v -> v < lo || v > hi);
                    if (vals.isEmpty() && k > 0) {
                        break;
                    }
                    for (double v : vals) {
                        out.add(new double[] {(v - sp0) / sp1, 0.5, (v - sc0) / sc1});
                    }
                }
            }
            double[] ends = fine ? FINE_END : FRACTIONS_END;
            double[] mids = fine ? FINE_MID : FRACTIONS_MID;
            for (double b2 : mids) {
                for (double b1 : ends) {
                    for (double b3 : ends) {
                        out.add(new double[] {b1, b2, b3});
                    }
                }
            }
            return out;
        }

        private static final class Scored {
            final double base;
            final double[] bars;
            final double[][] pts;

            Scored(double base, double[] bars, double[][] pts) {
                this.base = base;
                this.bars = bars;
                this.pts = pts;
            }
        }

        /** p → c の線を、障害物（占有範囲）とほかの線を避けて探す。見つからなければ null。 */
        static Route route(Box p, Box c, List<double[]> obstacles, List<Line> lines, boolean fine) {
            char mode = direction(p, c);
            if (mode == 0) {
                return null;
            }
            List<Scored> scored = new ArrayList<>();
            for (double[] bars : candidates(p, c, mode, fine)) {
                double[][] pts = linePoints(p, c, bars, mode);
                if (mode == 'v') {
                    boolean pUpper = p.bottom() <= c.top();
                    double upperB = pUpper ? Math.min(p.bottom(), c.bottom()) : c.bottom();
                    double lowerT = pUpper ? c.top() : p.top();
                    boolean inside = upperB + END_MIN <= pts[1][1] && pts[1][1] <= lowerT - END_MIN;
                    if (!inside && Math.abs(pts[1][0] - pts[2][0]) >= 1e-6) {
                        continue;
                    }
                }
                double e1 = Math.abs(pts[0][0] - pts[1][0]) + Math.abs(pts[0][1] - pts[1][1]);
                double em = Math.abs(pts[1][0] - pts[2][0]) + Math.abs(pts[1][1] - pts[2][1]);
                double e2 = Math.abs(pts[2][0] - pts[3][0]) + Math.abs(pts[2][1] - pts[3][1]);
                double base;
                if (em < 1e-6) {
                    if (e1 + e2 < END_MIN * 2 + 10) {
                        continue;
                    }
                    base = e1 + e2;
                } else {
                    if (e1 < END_MIN || e2 < END_MIN) {
                        continue;
                    }
                    base = e1 + em + e2 + 2 * BEND_COST;
                }
                scored.add(new Scored(base, bars, pts));
            }
            scored.sort(Comparator.comparingDouble(s -> s.base));

            // 線は両端のテーブルを囲む範囲の中にしか引かれないので、そこにかかるものだけ調べる
            double pad = CLEARANCE + SEARCH_MARGIN + PARALLEL_GAP + TERMINAL_ZONE;
            double[] area = {Math.min(p.x, c.x) - pad, Math.min(p.top(), c.top()) - pad,
                Math.max(p.right(), c.right()) + pad, Math.max(p.bottom(), c.bottom()) + pad};
            List<double[]> obs = new ArrayList<>();
            for (double[] r : obstacles) {
                if (r[0] < area[2] && r[2] > area[0] && r[1] < area[3] && r[3] > area[1]) {
                    obs.add(r);
                }
            }
            List<Line> near = new ArrayList<>();
            for (Line ln : lines) {
                for (double[] sg : ln.segments) {
                    if (segHitsRect(sg, area, 0)) {
                        near.add(ln);
                        break;
                    }
                }
            }

            Route best = null;
            double clear = CLEARANCE + SEARCH_MARGIN;
            for (Scored sc : scored) {
                if (best != null && sc.base >= best.cost) {
                    break;
                }
                Line line = toLine(sc.pts);
                boolean hit = false;
                for (double[] s : line.segments) {
                    double lx = Math.min(s[0], s[2]);
                    double hx = Math.max(s[0], s[2]);
                    double ly = Math.min(s[1], s[3]);
                    double hy = Math.max(s[1], s[3]);
                    for (double[] r : obs) {
                        if (lx < r[2] + clear && hx > r[0] - clear && ly < r[3] + clear && hy > r[1] - clear) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit) {
                        break;
                    }
                }
                if (hit) {
                    continue;
                }
                int crosses = 0;
                boolean ok = true;
                for (Line other : near) {
                    int n = compare(line, other, PARALLEL_GAP + SEARCH_MARGIN);
                    if (n < 0) {
                        ok = false;
                        break;
                    }
                    crosses += n;
                }
                if (!ok) {
                    continue;
                }
                double cost = sc.base + crosses * CROSS_COST;
                if (best == null || cost < best.cost) {
                    best = new Route(sc.bars, line, cost);
                }
            }
            return best;
        }

        static Route routeAny(Box p, Box c, List<double[]> obstacles, List<Line> lines) {
            Route r = route(p, c, obstacles, lines, false);
            return r != null ? r : route(p, c, obstacles, lines, true);
        }

        static boolean rectHitsLines(double[] r, List<Line> lines) {
            for (Line line : lines) {
                for (double[] s : line.segments) {
                    if (segHitsRect(s, r, CLEARANCE)) {
                        return true;
                    }
                }
            }
            return false;
        }

        static int[] barsToInt(double[] bars) {
            int[] out = new int[3];
            for (int i = 0; i < 3; i++) {
                out[i] = Math.max(0, Math.min(10000, rnd(bars[i] * 10000)));
            }
            return out;
        }
    }

    // =====================================================================
    // 整列（compact / layered / grid）と書き出し
    // =====================================================================

    static final class Options {
        String mode = "compact";
        int gapX = 60;
        int gapY = 100;
        int maxWidth = 3000;
        int margin = 50;
        boolean keepLines = false;
        /** ヘッダの無いリレーション CSV を論理名として読む（一括モード用）。 */
        boolean logicalRelations = false;
    }

    /** 整列の結果。 */
    static final class LayoutResult {
        final byte[] data;
        final int tables;
        final int pages;
        final int unrouted;

        LayoutResult(byte[] data, int tables, int pages, int unrouted) {
            this.data = data;
            this.tables = tables;
            this.pages = pages;
            this.unrouted = unrouted;
        }

        String summary() {
            String text = "整列しました: テーブル " + tables + " 個 / ページ " + pages;
            if (unrouted > 0) {
                text += " / 重ならずに線を引けなかったリレーション " + unrouted + " 本（線は元のまま）";
            }
            return text;
        }
    }

    static final class Layout {
        static final int MAX_TRIED_POSITIONS = 60;
        static final int COMPACT_VARIANTS = 4;

        private Layout() {
        }

        static LayoutResult layoutBytes(byte[] data, Options opt) throws ToolException {
            Document doc = parseDocument(data);
            if (doc.tables.isEmpty()) {
                throw new ToolException("テーブル（[Entity]）が見つかりません。.a5er ファイルか確認してください。");
            }
            int pages = arrange(doc, opt);
            return new LayoutResult(doc.file.encode(apply(doc)), doc.tables.size(), pages, doc.unrouted);
        }

        static int arrange(Document doc, Options opt) {
            Map<String, List<Placement>> pages = new LinkedHashMap<>();
            for (Table t : doc.tables) {
                for (Placement p : t.placements) {
                    pages.computeIfAbsent(p.page, k -> new ArrayList<>()).add(p);
                }
            }
            double k = doc.fontSize / BASE_FONT_SIZE;
            doc.unrouted = 0;
            for (Map.Entry<String, List<Placement>> e : pages.entrySet()) {
                doc.unrouted += layoutPage(e.getValue(), doc.relations, e.getKey(), opt, k);
            }
            return pages.size();
        }

        static final Comparator<Placement> BY_NAME = Comparator
                .comparing((Placement p) -> p.table.name().toLowerCase(Locale.ROOT))
                .thenComparing(p -> p.table.name());

        /** ページ内の Relation（自己参照以外）: [Relation の番号の代わりに使う並び順, 親, 子]。 */
        static final class Rel {
            final Relation rel;
            final int a;
            final int b;

            Rel(Relation rel, int a, int b) {
                this.rel = rel;
                this.a = a;
                this.b = b;
            }
        }

        static int layoutPage(List<Placement> nodes, List<Relation> relations, String page, Options opt, double k) {
            nodes.sort(BY_NAME);
            if (opt.mode.equals("grid")) {
                placeGrid(nodes, opt.margin, opt.margin + rnd(LABEL_HEIGHT * k), opt);
                return 0;
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                index.putIfAbsent(nodes.get(i).table.name(), i);
            }
            List<Rel> rels = new ArrayList<>();
            Set<Integer> loops = new HashSet<>();
            for (Relation rel : relations) {
                Integer a = index.get(rel.parent);
                Integer b = index.get(rel.child);
                if (a == null || b == null) {
                    continue;
                }
                if (a.equals(b)) {
                    loops.add(a);
                    continue;
                }
                rels.add(new Rel(rel, a, b));
            }
            int failed;
            if (opt.mode.equals("compact")) {
                failed = compactPage(nodes, rels, page, opt, k, loops);
            } else {
                for (int i : loops) { // 自己参照の線の分、右を空けて並べる
                    nodes.get(i).w += Router.SELF_LOOP_RIGHT;
                }
                layeredPage(nodes, rels, opt, k);
                for (int i : loops) {
                    nodes.get(i).w -= Router.SELF_LOOP_RIGHT;
                }
                failed = routeFixed(nodes, rels, page, k, loops);
            }
            if (opt.keepLines) {
                for (Rel r : rels) {
                    r.rel.routes.remove(page);
                }
                return 0;
            }
            return failed;
        }

        /** items を左から並べ、max_width で折り返す。[右端, 下端] を返す。 */
        static int[] placeGrid(List<Placement> items, int x0, int y0, Options opt) {
            int x = x0;
            int y = y0;
            int rowH = 0;
            int right = x0;
            for (Placement p : items) {
                if (x > x0 && x + p.w > x0 + opt.maxWidth) {
                    x = x0;
                    y = y + rowH + opt.gapY;
                    rowH = 0;
                }
                p.x = x;
                p.y = y;
                x += p.w + opt.gapX;
                rowH = Math.max(rowH, p.h);
                right = Math.max(right, p.x + p.w);
            }
            return new int[] {right, items.isEmpty() ? y0 : y + rowH};
        }

        static List<List<Integer>> components(int n, Map<Integer, Set<Integer>> adj) {
            Set<Integer> seen = new HashSet<>();
            List<List<Integer>> result = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (seen.contains(i)) {
                    continue;
                }
                Deque<Integer> stack = new ArrayDeque<>();
                List<Integer> comp = new ArrayList<>();
                stack.push(i);
                seen.add(i);
                while (!stack.isEmpty()) {
                    int v = stack.pop();
                    comp.add(v);
                    for (int u : adj.get(v)) {
                        if (seen.add(u)) {
                            stack.push(u);
                        }
                    }
                }
                result.add(comp);
            }
            return result;
        }

        static Router.Box toBox(Placement p, double k, boolean selfLoop, double x, double y) {
            return new Router.Box(x, y, p.w, p.h, rnd(LABEL_HEIGHT * k), rnd(TEXT_HEIGHT * k), p.labelW, selfLoop);
        }

        static Router.Box toBox(Placement p, double k, boolean selfLoop) {
            return toBox(p, k, selfLoop, p.x, p.y);
        }

        // --- layered -------------------------------------------------------

        /** 名前順の DFS で戻り辺を除いた辺（親 → 子の一覧）を返す。 */
        static Map<Integer, List<Integer>> removeCycles(List<Integer> comp, Map<Integer, List<Integer>> children) {
            Set<Integer> members = new HashSet<>(comp);
            Map<Integer, Integer> state = new HashMap<>(); // 1=探索中 2=完了
            Map<Integer, List<Integer>> dag = new HashMap<>();
            for (int v : comp) {
                dag.put(v, new ArrayList<>());
            }
            List<Integer> sorted = new ArrayList<>(comp);
            Collections.sort(sorted);
            for (int start : sorted) {
                if (state.containsKey(start)) {
                    continue;
                }
                state.put(start, 1);
                Deque<int[]> stack = new ArrayDeque<>(); // [頂点, 次に見る子の位置]
                stack.push(new int[] {start, 0});
                while (!stack.isEmpty()) {
                    int[] top = stack.peek();
                    List<Integer> ch = children.get(top[0]);
                    if (top[1] >= ch.size()) {
                        state.put(top[0], 2);
                        stack.pop();
                        continue;
                    }
                    int nxt = ch.get(top[1]++);
                    if (!members.contains(nxt)) {
                        continue;
                    }
                    Integer s = state.get(nxt);
                    if (s != null && s == 1) {
                        continue; // 戻り辺（循環）は無視
                    }
                    dag.get(top[0]).add(nxt);
                    if (s == null) {
                        state.put(nxt, 1);
                        stack.push(new int[] {nxt, 0});
                    }
                }
            }
            return dag;
        }

        /** 最長経路で段を決め、親の無いテーブルを子の直上まで下げる。 */
        static Map<Integer, Integer> assignLayers(List<Integer> comp, Map<Integer, List<Integer>> dag) {
            Map<Integer, List<Integer>> parents = new HashMap<>();
            for (int v : comp) {
                parents.put(v, new ArrayList<>());
            }
            for (int v : comp) {
                for (int u : dag.get(v)) {
                    parents.get(u).add(v);
                }
            }
            Map<Integer, Integer> indeg = new HashMap<>();
            Map<Integer, Integer> layer = new HashMap<>();
            for (int v : comp) {
                indeg.put(v, parents.get(v).size());
                layer.put(v, 0);
            }
            List<Integer> sorted = new ArrayList<>(comp);
            Collections.sort(sorted);
            Deque<Integer> queue = new ArrayDeque<>();
            for (int v : sorted) {
                if (indeg.get(v) == 0) {
                    queue.add(v);
                }
            }
            while (!queue.isEmpty()) {
                int v = queue.poll();
                for (int u : dag.get(v)) {
                    layer.put(u, Math.max(layer.get(u), layer.get(v) + 1));
                    indeg.put(u, indeg.get(u) - 1);
                    if (indeg.get(u) == 0) {
                        queue.add(u);
                    }
                }
            }
            for (int v : comp) {
                if (parents.get(v).isEmpty() && !dag.get(v).isEmpty()) {
                    int min = Integer.MAX_VALUE;
                    for (int c : dag.get(v)) {
                        min = Math.min(min, layer.get(c));
                    }
                    layer.put(v, min - 1);
                }
            }
            int low = Collections.min(layer.values());
            Map<Integer, Integer> out = new HashMap<>();
            for (int v : comp) {
                out.put(v, layer.get(v) - low);
            }
            return out;
        }

        /** 隣の段でつながる相手の並び位置の平均（重心）で段内を並べ替える。 */
        static void orderLayers(List<List<Integer>> layers, Map<Integer, List<Integer>> up, Map<Integer, List<Integer>> down) {
            int n = layers.size();
            for (int round = 0; round < 8; round++) {
                for (int li = 1; li < n; li++) {
                    sweep(layers, li, up);
                }
                for (int li = n - 2; li >= 0; li--) {
                    sweep(layers, li, down);
                }
            }
        }

        private static void sweep(List<List<Integer>> layers, int li, Map<Integer, List<Integer>> nbrs) {
            Map<Integer, Double> pos = new HashMap<>();
            for (List<Integer> row : layers) {
                for (int i = 0; i < row.size(); i++) {
                    pos.put(row.get(i), (i + 0.5) / row.size());
                }
            }
            Map<Integer, Double> key = new HashMap<>();
            for (int v : layers.get(li)) {
                List<Integer> ns = nbrs.getOrDefault(v, List.of());
                if (ns.isEmpty()) {
                    key.put(v, pos.get(v));
                } else {
                    double sum = 0;
                    for (int u : ns) {
                        sum += pos.get(u);
                    }
                    key.put(v, sum / ns.size());
                }
            }
            layers.get(li).sort(Comparator.comparingDouble(key::get));
        }

        static List<List<Integer>> buildRows(List<Placement> nodes, List<Integer> comp, Map<Integer, List<Integer>> dag,
                                             Map<Integer, Integer> layerOf, Options opt) {
            int depth = Collections.max(layerOf.values()) + 1;
            List<List<Integer>> layers = new ArrayList<>();
            for (int i = 0; i < depth; i++) {
                layers.add(new ArrayList<>());
            }
            List<Integer> sorted = new ArrayList<>(comp);
            Collections.sort(sorted);
            for (int v : sorted) {
                layers.get(layerOf.get(v)).add(v);
            }
            // 2 段以上離れた辺に仮の点を置く
            Map<Integer, List<Integer>> up = new HashMap<>();
            Map<Integer, List<Integer>> down = new HashMap<>();
            for (int v : comp) {
                up.put(v, new ArrayList<>());
                down.put(v, new ArrayList<>());
            }
            int nextId = nodes.size();
            for (int v : sorted) {
                for (int c : dag.get(v)) {
                    int prev = v;
                    for (int li = layerOf.get(v) + 1; li < layerOf.get(c); li++) {
                        int d = nextId++;
                        layers.get(li).add(d);
                        up.put(d, new ArrayList<>(List.of(prev)));
                        down.put(d, new ArrayList<>());
                        down.get(prev).add(d);
                        prev = d;
                    }
                    down.get(prev).add(c);
                    up.get(c).add(prev);
                }
            }
            orderLayers(layers, up, down);

            List<List<Integer>> rows = new ArrayList<>();
            for (List<Integer> layer : layers) {
                List<Integer> cur = new ArrayList<>();
                int width = 0;
                for (int v : layer) {
                    if (v >= nodes.size()) {
                        continue; // 仮の点
                    }
                    int w = nodes.get(v).w;
                    int add = cur.isEmpty() ? w : opt.gapX + w;
                    if (!cur.isEmpty() && width + add > opt.maxWidth) {
                        rows.add(cur);
                        cur = new ArrayList<>();
                        width = 0;
                        add = w;
                    }
                    cur.add(v);
                    width += add;
                }
                if (!cur.isEmpty()) {
                    rows.add(cur);
                }
            }
            return rows;
        }

        static void placeX(List<Placement> nodes, List<List<Integer>> rows, Map<Integer, List<Integer>> parents,
                           Map<Integer, Integer> rowOf, Options opt) {
            for (int r = 0; r < rows.size(); r++) {
                Integer right = null;
                for (int v : rows.get(r)) {
                    Placement p = nodes.get(v);
                    double sum = 0;
                    int cnt = 0;
                    for (int u : parents.get(v)) {
                        if (rowOf.get(u) < r) {
                            sum += nodes.get(u).x + nodes.get(u).w / 2.0;
                            cnt++;
                        }
                    }
                    Integer want = cnt > 0 ? rnd(sum / cnt - p.w / 2.0) : null;
                    int x;
                    if (right == null) {
                        x = want != null ? want : 0;
                    } else {
                        x = want != null ? Math.max(want, right + opt.gapX) : right + opt.gapX;
                    }
                    p.x = x;
                    right = x + p.w;
                }
            }
            int low = Integer.MAX_VALUE;
            for (List<Integer> row : rows) {
                for (int v : row) {
                    low = Math.min(low, nodes.get(v).x);
                }
            }
            for (List<Integer> row : rows) {
                for (int v : row) {
                    nodes.get(v).x -= low;
                }
            }
        }

        static int[] layoutComponent(List<Placement> nodes, List<Integer> comp, Map<Integer, List<Integer>> children,
                                     Options opt, double k) {
            Map<Integer, List<Integer>> dag = removeCycles(comp, children);
            Map<Integer, Integer> layerOf = assignLayers(comp, dag);
            List<List<Integer>> rows = buildRows(nodes, comp, dag, layerOf, opt);
            Map<Integer, Integer> rowOf = new HashMap<>();
            for (int r = 0; r < rows.size(); r++) {
                for (int v : rows.get(r)) {
                    rowOf.put(v, r);
                }
            }
            Map<Integer, List<Integer>> parents = new HashMap<>();
            for (int v : comp) {
                parents.put(v, new ArrayList<>());
            }
            for (int v : comp) {
                for (int c : dag.get(v)) {
                    parents.get(c).add(v);
                }
            }
            placeX(nodes, rows, parents, rowOf, opt);
            int gap = Math.max(opt.gapY, rnd(LABEL_HEIGHT * k) + 80);
            int y = 0;
            for (List<Integer> row : rows) {
                int rowH = 0;
                for (int v : row) {
                    nodes.get(v).y = y;
                    rowH = Math.max(rowH, nodes.get(v).h);
                }
                y += rowH + gap;
            }
            int width = 0;
            for (int v : comp) {
                width = Math.max(width, nodes.get(v).x + nodes.get(v).w);
            }
            return new int[] {width, y - gap};
        }

        static void layeredPage(List<Placement> nodes, List<Rel> rels, Options opt, double k) {
            int n = nodes.size();
            Map<Integer, List<Integer>> children = new HashMap<>();
            Map<Integer, Set<Integer>> adj = new HashMap<>();
            for (int i = 0; i < n; i++) {
                children.put(i, new ArrayList<>());
                adj.put(i, new LinkedHashSet<>());
            }
            for (Rel r : rels) {
                if (!children.get(r.a).contains(r.b)) {
                    children.get(r.a).add(r.b);
                }
                adj.get(r.a).add(r.b);
                adj.get(r.b).add(r.a);
            }
            for (List<Integer> ch : children.values()) {
                Collections.sort(ch);
            }
            List<List<Integer>> groups = new ArrayList<>();
            List<Integer> singles = new ArrayList<>();
            for (List<Integer> c : components(n, adj)) {
                if (c.size() > 1) {
                    List<Integer> s = new ArrayList<>(c);
                    Collections.sort(s);
                    groups.add(s);
                } else {
                    singles.add(c.get(0));
                }
            }
            Collections.sort(singles);
            groups.sort(Comparator.comparingInt((List<Integer> c) -> -c.size()).thenComparingInt(c -> c.get(0)));

            int x0 = opt.margin;
            int y0 = opt.margin + rnd(LABEL_HEIGHT * k);
            int x = x0;
            int y = y0;
            int shelfH = 0;
            int bottom = y0;
            int compGap = opt.gapX * 2;
            for (List<Integer> comp : groups) {
                int[] wh = layoutComponent(nodes, comp, children, opt, k);
                if (x > x0 && x + wh[0] > x0 + opt.maxWidth) {
                    x = x0;
                    y = y + shelfH + opt.gapY;
                    shelfH = 0;
                }
                for (int v : comp) {
                    nodes.get(v).x += x;
                    nodes.get(v).y += y;
                }
                x += wh[0] + compGap;
                shelfH = Math.max(shelfH, wh[1]);
                bottom = y + shelfH;
            }
            int startY = groups.isEmpty() ? y0 : bottom + opt.gapY + rnd(LABEL_HEIGHT * k);
            List<Placement> items = new ArrayList<>();
            for (int i : singles) {
                items.add(nodes.get(i));
            }
            placeGrid(items, x0, startY, opt);
        }

        /** テーブルを動かさずに線を探す。線を引けなかった数を返す。 */
        static int routeFixed(List<Placement> nodes, List<Rel> rels, String page, double k, Set<Integer> loops) {
            List<Router.Box> boxes = new ArrayList<>();
            List<double[]> occupied = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                Router.Box b = toBox(nodes.get(i), k, loops.contains(i));
                boxes.add(b);
                occupied.add(b.occupied());
            }
            List<Router.Line> lines = new ArrayList<>();
            int failed = 0;
            List<Rel> sorted = new ArrayList<>(rels);
            sorted.sort(Comparator.comparingDouble(r -> {
                double[] g = Router.gaps(boxes.get(r.a), boxes.get(r.b));
                return Math.max(g[0], g[1]);
            }));
            for (Rel r : sorted) {
                List<double[]> obstacles = new ArrayList<>();
                for (int i = 0; i < occupied.size(); i++) {
                    if (i != r.a && i != r.b) {
                        obstacles.add(occupied.get(i));
                    }
                }
                Router.Route route = Router.routeAny(boxes.get(r.a), boxes.get(r.b), obstacles, lines);
                if (route == null) {
                    failed++;
                    continue;
                }
                lines.add(route.line);
                r.rel.routes.put(page, Router.barsToInt(route.bars));
            }
            return failed;
        }

        // --- compact -------------------------------------------------------

        /** 線が重ならないように、全体を正方形に近くまとめる。置く順番を変えて何通りか試し、一番良いものを採る。 */
        static int compactPage(List<Placement> nodes, List<Rel> rels, String page, Options opt, double k, Set<Integer> loops) {
            Compact best = null;
            double[] bestKey = null;
            for (int variant = 0; variant < COMPACT_VARIANTS; variant++) {
                Compact c = new Compact(nodes, rels, opt, k, loops, variant);
                c.run();
                double[] key = {c.failed.size(), Math.max(c.width, c.height), c.width + c.height};
                if (bestKey == null || Arrays.compare(key, bestKey) < 0) {
                    best = c;
                    bestKey = key;
                }
            }
            for (Map.Entry<Integer, int[]> e : best.positions.entrySet()) {
                nodes.get(e.getKey()).x = e.getValue()[0];
                nodes.get(e.getKey()).y = e.getValue()[1];
            }
            for (Map.Entry<Integer, int[]> e : best.barsOf.entrySet()) {
                rels.get(e.getKey()).rel.routes.put(page, e.getValue());
            }
            return best.failed.size();
        }

        /** 1 通りの順番でのコンパクト配置。 */
        static final class Compact {
            final List<Placement> nodes;
            final List<Rel> rels;
            final Options opt;
            final double k;
            final Set<Integer> loops;
            final int variant;
            final int n;
            final Map<Integer, Set<Integer>> nbrs = new HashMap<>();
            final Map<Integer, Router.Box> boxes = new LinkedHashMap<>();
            final Map<Integer, Router.Line> lineOf = new LinkedHashMap<>();
            final Map<Integer, int[]> barsOf = new HashMap<>();
            final Set<Integer> failed = new TreeSet<>();
            final Map<Integer, int[]> positions = new HashMap<>();
            double width;
            double height;

            Compact(List<Placement> nodes, List<Rel> rels, Options opt, double k, Set<Integer> loops, int variant) {
                this.nodes = nodes;
                this.rels = rels;
                this.opt = opt;
                this.k = k;
                this.loops = loops;
                this.variant = variant;
                this.n = nodes.size();
                for (int i = 0; i < n; i++) {
                    nbrs.put(i, new HashSet<>());
                }
                for (Rel r : rels) {
                    nbrs.get(r.a).add(r.b);
                    nbrs.get(r.b).add(r.a);
                }
            }

            String name(int i) {
                return nodes.get(i).table.name();
            }

            long area(int i) {
                return (long) nodes.get(i).w * nodes.get(i).h;
            }

            Router.Box boxAt(int i, double x, double y) {
                return toBox(nodes.get(i), k, loops.contains(i), x, y);
            }

            double[] bbox() {
                double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (Router.Box box : boxes.values()) {
                    double[] o = box.occupied();
                    b[0] = Math.min(b[0], o[0]);
                    b[1] = Math.min(b[1], o[1]);
                    b[2] = Math.max(b[2], o[2]);
                    b[3] = Math.max(b[3], o[3]);
                }
                return b;
            }

            static double areaTerm(double[] b) {
                double w = b[2] - b[0];
                double h = b[3] - b[1];
                return 2 * Math.max(w, h) + 0.5 * (w + h);
            }

            List<int[]> candidates(int i, List<Integer> bases, int[] mults) {
                Placement p = nodes.get(i);
                double[] probe = boxAt(i, 0, 0).occupied();
                double occW = probe[2];
                double topOff = -probe[1];
                TreeSet<Long> out = new TreeSet<>();
                for (int j : bases) {
                    Router.Box nb = boxes.get(j);
                    double[] o = nb.occupied();
                    for (int m : mults) {
                        double dx = opt.gapX * m;
                        double dy = opt.gapY * m;
                        double[] ys = {nb.y, nb.y + (nb.h - p.h) / 2.0, nb.bottom() - p.h};
                        double[] xs = {nb.x, nb.x + (nb.w - p.w) / 2.0, nb.right() - p.w};
                        for (double yy : ys) {
                            add(out, o[2] + dx, yy);
                            add(out, o[0] - dx - occW, yy);
                        }
                        for (double xx : xs) {
                            add(out, xx, o[3] + dy + topOff);
                            add(out, xx, o[1] - dy - p.h);
                        }
                    }
                }
                // つながる相手が 2 つ以上: 「A の横位置 × B の縦位置」の組み合わせ
                if (bases.size() >= 2 && bases.size() <= 6) {
                    Set<Double> xsAll = new LinkedHashSet<>();
                    Set<Double> ysAll = new LinkedHashSet<>();
                    for (int j : bases) {
                        Router.Box nb = boxes.get(j);
                        double[] o = nb.occupied();
                        xsAll.addAll(List.of(nb.x, nb.x + (nb.w - p.w) / 2.0, o[2] + opt.gapX, o[0] - opt.gapX - occW));
                        ysAll.addAll(List.of(nb.y, nb.y + (nb.h - p.h) / 2.0, o[3] + opt.gapY + topOff, o[1] - opt.gapY - p.h));
                    }
                    for (double xx : xsAll) {
                        for (double yy : ysAll) {
                            add(out, xx, yy);
                        }
                    }
                }
                double[] b = bbox();
                add(out, b[2] + opt.gapX, b[1] + topOff);
                add(out, b[0], b[3] + opt.gapY + topOff);
                for (Router.Box q : boxes.values()) {
                    add(out, b[2] + opt.gapX, q.y);
                    add(out, q.x, b[3] + opt.gapY + topOff);
                }
                List<int[]> list = new ArrayList<>();
                for (long key : out) {
                    list.add(new int[] {(int) (key >> 32), (int) key});
                }
                return list;
            }

            private static void add(Set<Long> out, double x, double y) {
                out.add(((long) rnd(x) << 32) | (rnd(y) & 0xFFFFFFFFL));
            }

            /** 置き場所の候補と、確定する線。 */
            static final class Choice {
                final double score;
                final Router.Box box;
                final List<Object[]> routes; // [Relation の番号, Route または null]

                Choice(double score, Router.Box box, List<Object[]> routes) {
                    this.score = score;
                    this.box = box;
                    this.routes = routes;
                }
            }

            Choice tryPlace(int i, int[] mults, boolean strict, boolean fine, int limit) {
                List<Integer> bases = new ArrayList<>();
                for (int j : nbrs.get(i)) {
                    if (boxes.containsKey(j)) {
                        bases.add(j);
                    }
                }
                if (bases.isEmpty()) {
                    bases.addAll(boxes.keySet());
                }
                List<int[]> partners = new ArrayList<>(); // [Relation の番号, 親, 子]
                for (int e = 0; e < rels.size(); e++) {
                    Rel r = rels.get(e);
                    if ((r.a == i && boxes.containsKey(r.b)) || (r.b == i && boxes.containsKey(r.a))) {
                        partners.add(new int[] {e, r.a, r.b});
                    }
                }
                List<Router.Line> lines = new ArrayList<>(lineOf.values());
                double[] cur = bbox();
                double mx = opt.gapX / 2.0;
                double my = opt.gapY / 2.0;

                final class Cand {
                    final double lb;
                    final double at;
                    final int x;
                    final int y;
                    final Router.Box t;
                    final double[] o;

                    Cand(double lb, double at, int x, int y, Router.Box t, double[] o) {
                        this.lb = lb;
                        this.at = at;
                        this.x = x;
                        this.y = y;
                        this.t = t;
                        this.o = o;
                    }
                }
                List<Cand> scored = new ArrayList<>();
                for (int[] xy : candidates(i, bases, mults)) {
                    Router.Box t = boxAt(i, xy[0], xy[1]);
                    double[] o = t.occupied();
                    double[] nb = {Math.min(cur[0], o[0]), Math.min(cur[1], o[1]), Math.max(cur[2], o[2]), Math.max(cur[3], o[3])};
                    double at = areaTerm(nb);
                    double lb = at;
                    for (int[] pr : partners) {
                        Router.Box other = boxes.get(pr[1] == i ? pr[2] : pr[1]);
                        double[] g = Router.gaps(t, other);
                        lb += Math.max(0.0, Math.max(g[0], g[1]));
                    }
                    scored.add(new Cand(lb, at, xy[0], xy[1], t, o));
                }
                scored.sort(Comparator.comparingDouble((Cand c) -> c.lb).thenComparingInt(c -> c.x).thenComparingInt(c -> c.y));

                List<double[]> occupied = new ArrayList<>();
                for (Router.Box b : boxes.values()) {
                    occupied.add(b.occupied());
                }
                Choice best = null;
                int tried = 0;
                for (Cand cand : scored) {
                    if (best != null && cand.lb >= best.score) {
                        break;
                    }
                    if (tried >= limit) {
                        break;
                    }
                    double[] o = cand.o;
                    boolean overlap = false;
                    for (double[] q : occupied) {
                        if (o[0] < q[2] + mx && o[2] > q[0] - mx && o[1] < q[3] + my && o[3] > q[1] - my) {
                            overlap = true;
                            break;
                        }
                    }
                    if (overlap) {
                        continue;
                    }
                    if (strict && Router.rectHitsLines(o, lines)) {
                        continue;
                    }
                    tried++;
                    List<Router.Line> temp = new ArrayList<>(lines);
                    double total = 0;
                    List<Object[]> routes = new ArrayList<>();
                    boolean ok = true;
                    for (int[] pr : partners) {
                        int e = pr[0];
                        int a = pr[1];
                        int b = pr[2];
                        Router.Box pa = a == i ? cand.t : boxes.get(a);
                        Router.Box pb = b == i ? cand.t : boxes.get(b);
                        List<double[]> obstacles = new ArrayList<>();
                        for (Map.Entry<Integer, Router.Box> en : boxes.entrySet()) {
                            if (en.getKey() != a && en.getKey() != b) {
                                obstacles.add(en.getValue().occupied());
                            }
                        }
                        Router.Route r = Router.route(pa, pb, obstacles, temp, fine);
                        if (r == null) {
                            if (strict) {
                                ok = false;
                                break;
                            }
                            routes.add(new Object[] {e, null});
                            continue;
                        }
                        temp.add(r.line);
                        total += r.cost;
                        routes.add(new Object[] {e, r});
                    }
                    if (!ok) {
                        continue;
                    }
                    double score = cand.at + total;
                    if (best == null || score < best.score) {
                        best = new Choice(score, cand.t, routes);
                    }
                }
                return best;
            }

            void commit(int i, Choice c) {
                boxes.put(i, c.box);
                for (Object[] er : c.routes) {
                    int e = (Integer) er[0];
                    Router.Route r = (Router.Route) er[1];
                    if (r == null) {
                        failed.add(e);
                        lineOf.remove(e);
                        barsOf.remove(e);
                    } else {
                        failed.remove(e);
                        lineOf.put(e, r.line);
                        barsOf.put(e, Router.barsToInt(r.bars));
                    }
                }
            }

            Comparator<Integer> frontierOrder() {
                Comparator<Integer> placed = Comparator.comparingInt(i -> -placedNbrs(i));
                Comparator<Integer> deg = Comparator.comparingInt(i -> -nbrs.get(i).size());
                Comparator<Integer> big = Comparator.comparingLong(i -> -area(i));
                Comparator<Integer> small = Comparator.comparingLong(this::area);
                Comparator<Integer> nm = Comparator.comparing(this::name);
                switch (variant) {
                    case 1: return placed.thenComparing(big).thenComparing(deg).thenComparing(nm);
                    case 2: return deg.thenComparing(placed).thenComparing(big).thenComparing(nm);
                    case 3: return placed.thenComparing(deg).thenComparing(small).thenComparing(nm);
                    default: return placed.thenComparing(deg).thenComparing(big).thenComparing(nm);
                }
            }

            Comparator<Integer> seedOrder() {
                Comparator<Integer> deg = Comparator.comparingInt(i -> -nbrs.get(i).size());
                Comparator<Integer> big = Comparator.comparingLong(i -> -area(i));
                Comparator<Integer> small = Comparator.comparingLong(this::area);
                Comparator<Integer> nm = Comparator.comparing(this::name);
                switch (variant) {
                    case 1: return big.thenComparing(deg).thenComparing(nm);
                    case 3: return deg.thenComparing(small).thenComparing(nm);
                    default: return deg.thenComparing(big).thenComparing(nm);
                }
            }

            int placedNbrs(int i) {
                int c = 0;
                for (int j : nbrs.get(i)) {
                    if (boxes.containsKey(j)) {
                        c++;
                    }
                }
                return c;
            }

            Choice firstOf(List<Supplier<Choice>> tries) {
                for (Supplier<Choice> s : tries) {
                    Choice c = s.get();
                    if (c != null) {
                        return c;
                    }
                }
                return null;
            }

            void run() {
                while (boxes.size() < n) {
                    List<Integer> frontier = new ArrayList<>();
                    List<Integer> rest = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        if (boxes.containsKey(i)) {
                            continue;
                        }
                        rest.add(i);
                        if (placedNbrs(i) > 0) {
                            frontier.add(i);
                        }
                    }
                    int i = frontier.isEmpty() ? Collections.min(rest, seedOrder()) : Collections.min(frontier, frontierOrder());
                    if (boxes.isEmpty()) {
                        boxes.put(i, boxAt(i, 0, 0));
                        continue;
                    }
                    final int v = i;
                    Choice c = firstOf(List.of(
                        () -> tryPlace(v, new int[] {1, 2}, true, false, MAX_TRIED_POSITIONS),
                        () -> tryPlace(v, new int[] {1, 2}, true, true, MAX_TRIED_POSITIONS),
                        () -> tryPlace(v, new int[] {3, 4}, true, true, MAX_TRIED_POSITIONS),
                        () -> tryPlace(v, new int[] {1, 2, 3}, false, true, MAX_TRIED_POSITIONS)));
                    commit(i, c);
                }

                // 手直し: 線を引けなかった Relation の両端のどちらかを、全部の線が引ける場所へ置き直す
                for (int round = 0; round < 5 && !failed.isEmpty(); round++) {
                    for (int e : new ArrayList<>(failed)) {
                        if (!failed.contains(e)) {
                            continue;
                        }
                        Rel r = rels.get(e);
                        List<Integer> ends = new ArrayList<>(List.of(r.a, r.b));
                        ends.sort(Comparator.comparingInt((Integer v) -> nbrs.get(v).size())
                                .thenComparingLong(this::area).thenComparing(this::name));
                        for (int v : ends) {
                            if (boxes.size() == 1) {
                                break;
                            }
                            Router.Box saved = boxes.remove(v);
                            Map<Integer, Router.Line> savedLines = new HashMap<>();
                            for (int x = 0; x < rels.size(); x++) {
                                Rel rx = rels.get(x);
                                if ((rx.a == v || rx.b == v) && lineOf.containsKey(x)) {
                                    savedLines.put(x, lineOf.remove(x));
                                }
                            }
                            Choice c = firstOf(List.of(
                                () -> tryPlace(v, new int[] {1, 2}, true, true, Integer.MAX_VALUE),
                                () -> tryPlace(v, new int[] {3, 4}, true, true, Integer.MAX_VALUE),
                                () -> tryPlace(v, new int[] {5, 6}, true, true, Integer.MAX_VALUE)));
                            if (c != null) {
                                commit(v, c);
                                break;
                            }
                            boxes.put(v, saved);
                            lineOf.putAll(savedLines);
                        }
                    }
                }

                double[] b = bbox();
                double dx = opt.margin - b[0];
                double dy = opt.margin - b[1];
                for (Map.Entry<Integer, Router.Box> e : boxes.entrySet()) {
                    positions.put(e.getKey(), new int[] {rnd(e.getValue().x + dx), rnd(e.getValue().y + dy)});
                }
                width = b[2] - b[0];
                height = b[3] - b[1];
            }
        }

        // --- 書き出し --------------------------------------------------------

        static List<String> apply(Document doc) {
            List<String> lines = new ArrayList<>(doc.lines());
            for (Table t : doc.tables) {
                for (Placement p : t.placements) {
                    if (p.posIdx == null) {
                        continue;
                    }
                    String[] pos = parsePosition(valueOf(lines.get(p.posIdx)));
                    List<String> parts = new ArrayList<>();
                    parts.add(pos[0]);
                    parts.add(String.valueOf(p.x));
                    parts.add(String.valueOf(p.y));
                    for (int i = 3; i < pos.length; i++) {
                        parts.add(pos[i]);
                    }
                    lines.set(p.posIdx, replaceValue(lines.get(p.posIdx), String.join(",", parts)));
                }
                String mainPage = (t.page.isEmpty() ? DEFAULT_PAGE : t.page).toUpperCase(Locale.ROOT);
                Placement bound = t.placements.get(0);
                for (Placement p : t.placements) {
                    if (p.page.equals(mainPage)) {
                        bound = p;
                        break;
                    }
                }
                if (t.leftIdx != null) {
                    lines.set(t.leftIdx, replaceValue(lines.get(t.leftIdx), String.valueOf(bound.x)));
                }
                if (t.topIdx != null) {
                    lines.set(t.topIdx, replaceValue(lines.get(t.topIdx), String.valueOf(bound.y)));
                }
            }
            for (Relation rel : doc.relations) {
                applyRelation(lines, rel, doc.mainPage);
            }
            return lines;
        }

        static void applyRelation(List<String> lines, Relation rel, String mainPage) {
            for (Map.Entry<String, int[]> e : rel.routes.entrySet()) {
                String page = e.getKey();
                int[] bars = e.getValue();
                boolean found = false;
                for (int n = 0; n < rel.posLines.size(); n++) {
                    int idx = (Integer) rel.posLines.get(n)[0];
                    if (!rel.posLines.get(n)[1].equals(page)) {
                        continue;
                    }
                    String[] pos = parsePosition(valueOf(lines.get(idx)));
                    if (pos.length < 5) {
                        continue;
                    }
                    pos[2] = String.valueOf(bars[0]);
                    pos[3] = String.valueOf(bars[1]);
                    pos[4] = String.valueOf(bars[2]);
                    lines.set(idx, replaceValue(lines.get(idx), String.join(",", pos)));
                    found = n == 0;
                    break;
                }
                if (found || (rel.posLines.isEmpty() && page.equals(mainPage.toUpperCase(Locale.ROOT)))) {
                    for (int i = 1; i <= 3; i++) {
                        Integer idx = rel.barIdx.get(i);
                        if (idx != null) {
                            lines.set(idx, replaceValue(lines.get(idx), String.valueOf(rnd(bars[i - 1] / 10.0))));
                        }
                    }
                }
            }
        }
    }

    // =====================================================================
    // 論理名・リレーションの CSV と一括モード
    // =====================================================================

    static final List<String> NAMES_HEADER = List.of("テーブル物理名", "カラム物理名", "論理名");
    static final List<String> COMMENTS_HEADER = List.of("テーブル物理名", "カラム物理名", "コメント");
    static final List<String> RELATIONS_HEADER = List.of("親テーブル", "子テーブル", "親カラム", "子カラム", "親側種別", "子側種別", "依存");
    static final List<String> LOGICAL_RELATIONS_HEADER = List.of("親テーブル論理名", "子テーブル論理名", "親カラム論理名", "子カラム論理名",
            "親側種別", "子側種別", "依存");

    static final class CsvTools {
        private static final Pattern FIRST_TOKEN = Pattern.compile("^(\"(?:[^\"]|\"\")*\"|[^,]*)(.*)$", Pattern.DOTALL);

        private CsvTools() {
        }

        static final class Column {
            final int idx;
            String logical;
            final String physical;
            String comment;

            Column(int idx, String logical, String physical, String comment) {
                this.idx = idx;
                this.logical = logical;
                this.physical = physical;
                this.comment = comment;
            }
        }

        static final class Ent {
            String pname = "";
            String page = "";
            String lname = "";
            String comment = "";
            Integer pnameIdx;
            Integer lnameIdx;
            Integer commentIdx;
            final List<Column> columns = new ArrayList<>();

            Column findColumn(String name) {
                String key = name.strip().toLowerCase(Locale.ROOT);
                for (Column c : columns) {
                    if (c.physical.toLowerCase(Locale.ROOT).equals(key)) {
                        return c;
                    }
                }
                return null;
            }
        }

        static final class Rel {
            String entity1 = "";
            String entity2 = "";
            String fields1 = "";
            String fields2 = "";
            String type1 = "";
            String type2 = "";
            String dependence = "";
        }

        static final class Model {
            final TextFile file;
            final List<Ent> entities;
            final List<Rel> relations;
            final int maxZorder;

            Model(TextFile file, List<Ent> entities, List<Rel> relations, int maxZorder) {
                this.file = file;
                this.entities = entities;
                this.relations = relations;
                this.maxZorder = maxZorder;
            }

            List<Ent> findEntities(String name) {
                String key = name.strip().toLowerCase(Locale.ROOT);
                List<Ent> out = new ArrayList<>();
                for (Ent e : entities) {
                    if (e.pname.toLowerCase(Locale.ROOT).equals(key)) {
                        out.add(e);
                    }
                }
                return out;
            }
        }

        static final class Report {
            int done;
            int unchanged;
            final List<String> skipped = new ArrayList<>();

            void skip(int lineNo, String reason) {
                skipped.add("  " + lineNo + " 行目: " + reason);
            }

            List<String> lines(String doneLabel) {
                List<String> out = new ArrayList<>();
                out.add(doneLabel + " " + done + " 件 / 変更なし " + unchanged + " 件 / スキップ " + skipped.size() + " 件");
                if (!skipped.isEmpty()) {
                    out.add("スキップした行:");
                    out.addAll(skipped);
                }
                return out;
            }
        }

        /** 処理結果（出力のバイト列と表示する行）。 */
        static final class Result {
            final byte[] data;
            final List<String> log;

            Result(byte[] data, List<String> log) {
                this.data = data;
                this.log = log;
            }
        }

        static Model loadModel(byte[] data) throws ToolException {
            TextFile file = TextFile.decode(data);
            List<String> lines = file.lines;
            List<Ent> entities = new ArrayList<>();
            List<Rel> relations = new ArrayList<>();
            int maxZ = 0;
            Ent ent = null;
            Rel rel = null;
            for (int i = 0; i < lines.size(); i++) {
                String body = splitEol(lines.get(i))[0];
                String stripped = body.strip();
                if (isSectionHeader(stripped)) {
                    String section = stripped.substring(1, stripped.length() - 1);
                    ent = null;
                    rel = null;
                    if (TABLE_SECTIONS.contains(section)) {
                        ent = new Ent();
                        entities.add(ent);
                    } else if (section.equals("Relation")) {
                        rel = new Rel();
                        relations.add(rel);
                    }
                    continue;
                }
                if (!body.contains("=") || body.startsWith("#")) {
                    continue;
                }
                String[] kv = body.split("=", 2);
                String key = kv[0].strip();
                String value = kv[1];
                if (key.equals("ZOrder")) {
                    Integer z = toInt(value);
                    if (z != null) {
                        maxZ = Math.max(maxZ, z);
                    }
                }
                if (ent != null) {
                    switch (key) {
                        case "PName": ent.pname = value; ent.pnameIdx = i; break;
                        case "LName": ent.lname = value; ent.lnameIdx = i; break;
                        case "Comment": ent.comment = value; ent.commentIdx = i; break;
                        case "Page": ent.page = value; break;
                        case "Field": {
                            List<String> cols = Csv.parseLine(value);
                            ent.columns.add(new Column(i, cols.isEmpty() ? "" : cols.get(0), cols.size() > 1 ? cols.get(1) : "",
                                    cols.size() > 6 ? cols.get(6) : ""));
                            break;
                        }
                        default: break;
                    }
                } else if (rel != null) {
                    switch (key) {
                        case "Entity1": rel.entity1 = value; break;
                        case "Entity2": rel.entity2 = value; break;
                        case "Fields1": rel.fields1 = value; break;
                        case "Fields2": rel.fields2 = value; break;
                        case "RelationType1": rel.type1 = value; break;
                        case "RelationType2": rel.type2 = value; break;
                        case "Dependence": rel.dependence = value; break;
                        default: break;
                    }
                }
            }
            entities.removeIf(e -> e.pname.isEmpty());
            relations.removeIf(r -> r.entity1.isEmpty() || r.entity2.isEmpty());
            if (entities.isEmpty()) {
                throw new ToolException("テーブル（[Entity]）が見つかりません。.a5er ファイルか確認してください。");
            }
            return new Model(file, entities, relations, maxZ);
        }

        static String replaceFirstToken(String line, String newText) {
            String[] be = splitEol(line);
            String[] kv = be[0].split("=", 2);
            Matcher m = FIRST_TOKEN.matcher(kv[1]);
            m.matches();
            return kv[0] + "=" + quote(newText) + m.group(2) + be[1];
        }

        /** (CSV の行番号, 列) の一覧。ヘッダ行と空行は除き、列数は header にそろえる。 */
        static List<Csv.Row> readCsv(byte[] data, List<String> header) throws ToolException {
            List<Csv.Row> out = new ArrayList<>();
            for (Csv.Row row : Csv.parse(Csv.decode(data))) {
                List<String> cells = new ArrayList<>();
                for (String c : row.cells) {
                    cells.add(c.strip());
                }
                if (row.lineNo == 1 && cells.size() >= header.size() && cells.subList(0, header.size()).equals(header)) {
                    continue;
                }
                if (cells.stream().allMatch(String::isEmpty)) {
                    continue;
                }
                while (cells.size() < header.size()) {
                    cells.add("");
                }
                out.add(new Csv.Row(row.lineNo, cells));
            }
            return out;
        }

        // --- 論理名 ---

        static Result exportNames(Model model) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(NAMES_HEADER);
            for (Ent e : model.entities) {
                rows.add(List.of(e.pname, "", e.lname));
                for (Column c : e.columns) {
                    rows.add(List.of(e.pname, c.physical, c.logical));
                }
            }
            return new Result(Csv.write(rows), List.of("論理名を書き出しました: " + (rows.size() - 1) + " 行"));
        }

        static Report lastReport;

        static Result importNames(Model model, byte[] csvData) throws ToolException {
            List<String> lines = new ArrayList<>(model.file.lines);
            Map<Integer, String> inserts = new TreeMap<>(); // PName 行の後ろに足す LName 行
            Report rep = new Report();
            for (Csv.Row row : readCsv(csvData, NAMES_HEADER)) {
                String table = row.cells.get(0);
                String column = row.cells.get(1);
                String logical = row.cells.get(2);
                if (logical.isEmpty()) {
                    continue;
                }
                List<Ent> ents = model.findEntities(table);
                if (ents.isEmpty()) {
                    rep.skip(row.lineNo, "テーブル「" + table + "」がありません");
                    continue;
                }
                if (column.isEmpty()) {
                    for (Ent e : ents) {
                        if (e.lname.equals(logical)) {
                            rep.unchanged++;
                            continue;
                        }
                        if (e.lnameIdx != null) {
                            lines.set(e.lnameIdx, replaceValue(lines.get(e.lnameIdx), logical));
                        } else {
                            String eol = splitEol(lines.get(e.pnameIdx))[1];
                            inserts.put(e.pnameIdx, "LName=" + logical + (eol.isEmpty() ? model.file.eol() : eol));
                        }
                        e.lname = logical;
                        rep.done++;
                    }
                    continue;
                }
                List<Column> hits = new ArrayList<>();
                for (Ent e : ents) {
                    Column c = e.findColumn(column);
                    if (c != null) {
                        hits.add(c);
                    }
                }
                if (hits.isEmpty()) {
                    rep.skip(row.lineNo, "テーブル「" + table + "」にカラム「" + column + "」がありません");
                    continue;
                }
                for (Column c : hits) {
                    if (c.logical.equals(logical)) {
                        rep.unchanged++;
                        continue;
                    }
                    lines.set(c.idx, replaceFirstToken(lines.get(c.idx), logical));
                    c.logical = logical;
                    rep.done++;
                }
            }
            lastReport = rep;
            return new Result(model.file.encode(insertLines(lines, inserts, model.file)), rep.lines("論理名を反映"));
        }

        /** inserts のキーの行の直後に、その値の行を足す。 */
        static List<String> insertLines(List<String> lines, Map<Integer, String> inserts, TextFile file) {
            if (inserts.isEmpty()) {
                return lines;
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (inserts.containsKey(i) && !line.endsWith("\n")) {
                    line = line + file.eol();
                }
                out.add(line);
                if (inserts.containsKey(i)) {
                    out.add(inserts.get(i));
                }
            }
            return out;
        }

        // --- コメント ---

        static Result exportComments(Model model) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(COMMENTS_HEADER);
            for (Ent e : model.entities) {
                rows.add(List.of(e.pname, "", e.comment));
                for (Column c : e.columns) {
                    rows.add(List.of(e.pname, c.physical, c.comment));
                }
            }
            return new Result(Csv.write(rows), List.of("コメントを書き出しました: " + (rows.size() - 1) + " 行"));
        }

        /** A5:SQL Mk-2 は改行を「\n」の 2 文字で持つ。セル内の本当の改行をそれに置き換える。 */
        static String escapeNewlines(String s) {
            return s.replace("\r\n", "\\n").replace("\r", "\\n").replace("\n", "\\n");
        }

        /** Field= の値を、項目の字句（引用符つきのまま）に分ける。 */
        static List<String> fieldTokens(String value) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuote = false;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '"') {
                    inQuote = !inQuote;
                } else if (ch == ',' && !inQuote) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
                cur.append(ch);
            }
            out.add(cur.toString());
            return out;
        }

        /** Field= 行の 7 番目（コメント）だけ書き換える。 */
        static String replaceFieldComment(String line, String comment) {
            String[] be = splitEol(line);
            String[] kv = be[0].split("=", 2);
            List<String> tokens = fieldTokens(kv[1]);
            while (tokens.size() < 7) {
                tokens.add("\"\"");
            }
            tokens.set(6, quote(comment));
            return kv[0] + "=" + String.join(",", tokens) + be[1];
        }

        static Result importComments(Model model, byte[] csvData) throws ToolException {
            List<String> lines = new ArrayList<>(model.file.lines);
            Map<Integer, String> inserts = new TreeMap<>(); // LName（無ければ PName）行の後ろに足す Comment 行
            Report rep = new Report();
            for (Csv.Row row : readCsv(csvData, COMMENTS_HEADER)) {
                String table = row.cells.get(0);
                String column = row.cells.get(1);
                String comment = escapeNewlines(row.cells.get(2));
                if (comment.isEmpty()) {
                    continue;
                }
                List<Ent> ents = model.findEntities(table);
                if (ents.isEmpty()) {
                    rep.skip(row.lineNo, "テーブル「" + table + "」がありません");
                    continue;
                }
                if (column.isEmpty()) {
                    for (Ent e : ents) {
                        if (e.comment.equals(comment)) {
                            rep.unchanged++;
                            continue;
                        }
                        if (e.commentIdx != null) {
                            lines.set(e.commentIdx, replaceValue(lines.get(e.commentIdx), comment));
                        } else {
                            int after = e.lnameIdx != null ? e.lnameIdx : e.pnameIdx;
                            String eol = splitEol(lines.get(after))[1];
                            inserts.put(after, "Comment=" + comment + (eol.isEmpty() ? model.file.eol() : eol));
                        }
                        e.comment = comment;
                        rep.done++;
                    }
                    continue;
                }
                List<Column> hits = new ArrayList<>();
                for (Ent e : ents) {
                    Column c = e.findColumn(column);
                    if (c != null) {
                        hits.add(c);
                    }
                }
                if (hits.isEmpty()) {
                    rep.skip(row.lineNo, "テーブル「" + table + "」にカラム「" + column + "」がありません");
                    continue;
                }
                for (Column c : hits) {
                    if (c.comment.equals(comment)) {
                        rep.unchanged++;
                        continue;
                    }
                    lines.set(c.idx, replaceFieldComment(lines.get(c.idx), comment));
                    c.comment = comment;
                    rep.done++;
                }
            }
            lastReport = rep;
            return new Result(model.file.encode(insertLines(lines, inserts, model.file)), rep.lines("コメントを反映"));
        }

        // --- リレーション ---

        static List<String> splitFields(String s) {
            List<String> out = new ArrayList<>();
            for (String f : s.split(",")) {
                if (!f.strip().isEmpty()) {
                    out.add(f.strip());
                }
            }
            return out;
        }

        static String relationKey(String parent, String child, List<String> f1, List<String> f2) {
            return (parent + "\u0000" + child + "\u0000" + String.join(",", f1) + "\u0000" + String.join(",", f2))
                    .toLowerCase(Locale.ROOT);
        }

        static Result exportRelations(Model model) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(RELATIONS_HEADER);
            for (Rel r : model.relations) {
                rows.add(List.of(r.entity1, r.entity2, r.fields1, r.fields2, r.type1, r.type2, r.dependence));
            }
            return new Result(Csv.write(rows), List.of("リレーションを書き出しました: " + (rows.size() - 1) + " 件"));
        }

        static List<String> relationBlock(Ent parent, Ent child, List<String> f1, List<String> f2,
                                          String t1, String t2, String dep, int zorder, String eol) {
            String page = parent.page.isEmpty() ? DEFAULT_PAGE : parent.page;
            List<String> body = List.of(
                "[Relation]",
                "Entity1=" + parent.pname,
                "Entity2=" + child.pname,
                "RelationType1=" + (t1.isEmpty() ? "2" : t1),
                "RelationType2=" + (t2.isEmpty() ? "3" : t2),
                "Fields1=" + String.join(",", f1),
                "Fields2=" + String.join(",", f2),
                "Cardinarity1=",
                "Cardinarity2=",
                "Position=" + quote(page) + ",0,5000,5000,5000,R,R,\"\"",
                "Dependence=" + (dep.isEmpty() ? "0" : dep),
                "Caption=",
                "PName=",
                "LineMode=0",
                "Bar1=500",
                "Bar2=500",
                "Bar3=500",
                "TermPos1=R",
                "TermPos2=R",
                "ZOrder=" + zorder);
            List<String> out = new ArrayList<>();
            for (String s : body) {
                out.add(s + eol);
            }
            return out;
        }

        static final String AMBIGUOUS = "\u0000ambiguous";

        /** 論理名でテーブルを探す。無ければ物理名で探す。複数あれば null（決められない）。 */
        static Ent entityByLogical(Model model, String name) {
            String key = name.strip().toLowerCase(Locale.ROOT);
            List<Ent> hits = new ArrayList<>();
            for (Ent e : model.entities) {
                if (e.lname.strip().toLowerCase(Locale.ROOT).equals(key)) {
                    hits.add(e);
                }
            }
            if (hits.isEmpty()) {
                List<Ent> phys = model.findEntities(name);
                return phys.isEmpty() ? null : phys.get(0);
            }
            return hits.size() == 1 ? hits.get(0) : AMBIGUOUS_ENT;
        }

        static final Ent AMBIGUOUS_ENT = new Ent();

        /** 論理名でカラムを探し、物理名を返す。無ければ物理名で探す。見つからなければ null、複数あれば AMBIGUOUS。 */
        static String columnByLogical(Ent e, String name) {
            String key = name.strip().toLowerCase(Locale.ROOT);
            List<Column> hits = new ArrayList<>();
            for (Column c : e.columns) {
                if (c.logical.strip().toLowerCase(Locale.ROOT).equals(key)) {
                    hits.add(c);
                }
            }
            if (hits.isEmpty()) {
                Column c = e.findColumn(name);
                return c == null ? null : c.physical;
            }
            return hits.size() == 1 ? hits.get(0).physical : AMBIGUOUS;
        }

        static Result exportRelationsLogical(Model model) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(LOGICAL_RELATIONS_HEADER);
            int fallback = 0;
            for (Rel r : model.relations) {
                List<Ent> p = model.findEntities(r.entity1);
                List<Ent> c = model.findEntities(r.entity2);
                Ent parent = p.isEmpty() ? null : p.get(0);
                Ent child = c.isEmpty() ? null : c.get(0);
                String[] t1 = logicalTable(parent, r.entity1);
                String[] t2 = logicalTable(child, r.entity2);
                String[] f1 = logicalFields(parent, r.fields1);
                String[] f2 = logicalFields(child, r.fields2);
                fallback += Integer.parseInt(t1[1]) + Integer.parseInt(t2[1]) + Integer.parseInt(f1[1]) + Integer.parseInt(f2[1]);
                rows.add(List.of(t1[0], t2[0], f1[0], f2[0], r.type1, r.type2, r.dependence));
            }
            List<String> log = new ArrayList<>();
            log.add("リレーションを論理名で書き出しました: " + (rows.size() - 1) + " 件");
            if (fallback > 0) {
                log.add("論理名が無いため物理名で書いた箇所: " + fallback);
            }
            return new Result(Csv.write(rows), log);
        }

        /** [書く名前, 物理名で代用した数] */
        private static String[] logicalTable(Ent e, String physical) {
            if (e == null || e.lname.isBlank()) {
                return new String[] {physical, "1"};
            }
            return new String[] {e.lname, "0"};
        }

        private static String[] logicalFields(Ent e, String fields) {
            List<String> out = new ArrayList<>();
            int fallback = 0;
            for (String f : splitFields(fields)) {
                Column c = e == null ? null : e.findColumn(f);
                if (c == null || c.logical.isBlank()) {
                    out.add(f);
                    fallback++;
                } else {
                    out.add(c.logical);
                }
            }
            return new String[] {String.join(",", out), String.valueOf(fallback)};
        }

        /** リレーション CSV が論理名か。ヘッダで決め、ヘッダが無ければ ifNoHeader。 */
        static boolean isLogicalRelations(byte[] csvData, boolean ifNoHeader) throws ToolException {
            for (Csv.Row row : Csv.parse(Csv.decode(csvData))) {
                if (row.cells.stream().allMatch(s -> s.strip().isEmpty())) {
                    continue;
                }
                String first = row.cells.get(0).strip();
                if (first.equals(LOGICAL_RELATIONS_HEADER.get(0))) {
                    return true;
                }
                if (first.equals(RELATIONS_HEADER.get(0))) {
                    return false;
                }
                return ifNoHeader;
            }
            return ifNoHeader;
        }

        static Result importRelations(Model model, byte[] csvData) throws ToolException {
            return importRelations(model, csvData, false);
        }

        /** リレーションを追加する。CSV が論理名なら物理名に直してから追加する。 */
        static Result importRelations(Model model, byte[] csvData, boolean logicalIfNoHeader) throws ToolException {
            boolean logical = isLogicalRelations(csvData, logicalIfNoHeader);
            Report rep = new Report();
            Set<String> existing = new HashSet<>();
            for (Rel r : model.relations) {
                existing.add(relationKey(r.entity1, r.entity2, splitFields(r.fields1), splitFields(r.fields2)));
            }
            String eol = model.file.eol();
            List<String> newLines = new ArrayList<>();
            int zorder = model.maxZorder;
            for (Csv.Row row : readCsv(csvData, logical ? LOGICAL_RELATIONS_HEADER : RELATIONS_HEADER)) {
                List<String> c = row.cells;
                String pName = c.get(0);
                String cName = c.get(1);
                Ent parent;
                Ent child;
                if (logical) {
                    parent = entityByLogical(model, pName);
                    child = entityByLogical(model, cName);
                    if (parent == AMBIGUOUS_ENT || child == AMBIGUOUS_ENT) {
                        rep.skip(row.lineNo, "論理名「" + (parent == AMBIGUOUS_ENT ? pName : cName) + "」のテーブルが複数あります");
                        continue;
                    }
                } else {
                    List<Ent> parents = model.findEntities(pName);
                    List<Ent> children = model.findEntities(cName);
                    parent = parents.isEmpty() ? null : parents.get(0);
                    child = children.isEmpty() ? null : children.get(0);
                }
                if (parent == null) {
                    rep.skip(row.lineNo, "親テーブル「" + pName + "」がありません");
                    continue;
                }
                if (child == null) {
                    rep.skip(row.lineNo, "子テーブル「" + cName + "」がありません");
                    continue;
                }
                List<String> f1 = splitFields(c.get(2));
                List<String> f2 = splitFields(c.get(3));
                if (logical) {
                    List<String> amb = new ArrayList<>();
                    f1 = toPhysical(parent, f1, amb);
                    f2 = toPhysical(child, f2, amb);
                    if (!amb.isEmpty()) {
                        rep.skip(row.lineNo, "論理名が同じカラムが複数あります: " + String.join(", ", amb));
                        continue;
                    }
                }
                if (f1.isEmpty() || f2.isEmpty()) {
                    rep.skip(row.lineNo, "親カラム・子カラムが空です");
                    continue;
                }
                if (f1.size() != f2.size()) {
                    rep.skip(row.lineNo, "親カラムと子カラムの個数が違います");
                    continue;
                }
                List<String> missing = new ArrayList<>();
                for (String f : f1) {
                    if (parent.findColumn(f) == null) {
                        missing.add(parent.pname + "." + f);
                    }
                }
                for (String f : f2) {
                    if (child.findColumn(f) == null) {
                        missing.add(child.pname + "." + f);
                    }
                }
                if (!missing.isEmpty()) {
                    rep.skip(row.lineNo, "カラムがありません: " + String.join(", ", missing));
                    continue;
                }
                List<String> p1 = new ArrayList<>();
                for (String f : f1) {
                    p1.add(parent.findColumn(f).physical);
                }
                List<String> p2 = new ArrayList<>();
                for (String f : f2) {
                    p2.add(child.findColumn(f).physical);
                }
                String key = relationKey(parent.pname, child.pname, p1, p2);
                if (existing.contains(key)) {
                    rep.unchanged++;
                    continue;
                }
                existing.add(key);
                zorder++;
                newLines.add(eol);
                newLines.addAll(relationBlock(parent, child, p1, p2, c.get(4), c.get(5), c.get(6), zorder, eol));
                rep.done++;
            }
            List<String> lines = new ArrayList<>(model.file.lines);
            if (!newLines.isEmpty()) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).endsWith("\n")) {
                    lines.set(lines.size() - 1, lines.get(lines.size() - 1) + eol);
                }
                if (!lines.isEmpty() && splitEol(lines.get(lines.size() - 1))[0].strip().isEmpty()) {
                    newLines = newLines.subList(1, newLines.size()); // 末尾がすでに空行なら空行を足さない
                }
                lines.addAll(newLines);
            }
            lastReport = rep;
            List<String> log = new ArrayList<>();
            if (logical) {
                log.add("（リレーション CSV は論理名で指定）");
            }
            log.addAll(rep.lines("リレーションを追加"));
            return new Result(model.file.encode(lines), log);
        }

        /** カラムの論理名を物理名に直す。見つからないものはそのまま残す（後の「カラムがありません」で報告）。 */
        private static List<String> toPhysical(Ent e, List<String> names, List<String> ambiguous) {
            List<String> out = new ArrayList<>();
            for (String n : names) {
                String p = columnByLogical(e, n);
                if (p == AMBIGUOUS) {
                    ambiguous.add(e.pname + "." + n);
                    out.add(n);
                } else {
                    out.add(p == null ? n : p);
                }
            }
            return out;
        }

        // --- 一括 ---

        /** CSV の種類（"names" / "comments" / "relations"）をヘッダ行（無ければ列数）で見分ける。 */
        static String csvKind(byte[] data) throws ToolException {
            for (Csv.Row row : Csv.parse(Csv.decode(data))) {
                List<String> cells = new ArrayList<>();
                for (String c : row.cells) {
                    cells.add(c.strip());
                }
                if (cells.stream().allMatch(String::isEmpty)) {
                    continue;
                }
                if (cells.get(0).equals(NAMES_HEADER.get(0))) {
                    return cells.size() > 2 && cells.get(2).equals(COMMENTS_HEADER.get(2)) ? "comments" : "names";
                }
                if (cells.get(0).equals(RELATIONS_HEADER.get(0)) || cells.get(0).equals(LOGICAL_RELATIONS_HEADER.get(0))) {
                    return "relations";
                }
                return cells.size() <= 3 ? "names" : "relations";
            }
            return "names";
        }

        /** 論理名の反映 → コメントの反映 → リレーションの追加 → 整列 を順に行う。CSV は null なら使わない。 */
        static Result runAll(byte[] a5er, byte[] names, byte[] comments, byte[] relations, Options opt) throws ToolException {
            List<String> log = new ArrayList<>();
            byte[] data = a5er;
            if (names != null) {
                Result r = importNames(loadModel(data), names);
                data = r.data;
                log.addAll(r.log);
            }
            if (comments != null) {
                Result r = importComments(loadModel(data), comments);
                data = r.data;
                log.addAll(r.log);
            }
            if (relations != null) {
                Result r = importRelations(loadModel(data), relations, opt.logicalRelations);
                data = r.data;
                log.addAll(r.log);
            }
            LayoutResult lr = Layout.layoutBytes(data, opt);
            log.add(lr.summary());
            return new Result(lr.data, log);
        }
    }

    // =====================================================================
    // 出力先の名前・ファイル
    // =====================================================================

    static Path withSuffix(Path path, String suffix, String ext) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return path.resolveSibling(base + "_" + suffix + ext);
    }

    static byte[] read(Path p) throws ToolException {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new ToolException("ファイルを読めません: " + p);
        }
    }

    static void write(Path p, byte[] data) throws ToolException {
        try {
            Files.write(p, data);
        } catch (IOException e) {
            throw new ToolException("ファイルを書けません（開いたままなら閉じてください）: " + p);
        }
    }

    static void checkOutput(Path dst, List<Path> inputs) throws ToolException {
        for (Path in : inputs) {
            if (in != null && dst.toAbsolutePath().normalize().equals(in.toAbsolutePath().normalize())) {
                throw new ToolException("出力先に入力ファイルと同じパスは指定できません。");
            }
        }
    }

    /** 画面とコマンドで共通の処理。表示する行を返す。 */
    static final class Actions {
        private Actions() {
        }

        static List<String> layout(Path a5er, Path out, Options opt) throws ToolException {
            Path dst = out != null ? out : withSuffix(a5er, "layout", ".a5er");
            checkOutput(dst, List.of(a5er));
            LayoutResult r = Layout.layoutBytes(read(a5er), opt);
            write(dst, r.data);
            return List.of(r.summary(), "出力: " + dst);
        }

        static List<String> exportNames(Path a5er, Path out) throws ToolException {
            Path dst = out != null ? out : withSuffix(a5er, "names", ".csv");
            checkOutput(dst, List.of(a5er));
            CsvTools.Result r = CsvTools.exportNames(CsvTools.loadModel(read(a5er)));
            write(dst, r.data);
            return withOutput(r.log, dst);
        }

        static List<String> exportRelations(Path a5er, Path out) throws ToolException {
            return exportRelations(a5er, out, false);
        }

        static List<String> exportRelations(Path a5er, Path out, boolean logical) throws ToolException {
            Path dst = out != null ? out : withSuffix(a5er, logical ? "relations_logical" : "relations", ".csv");
            checkOutput(dst, List.of(a5er));
            CsvTools.Model model = CsvTools.loadModel(read(a5er));
            CsvTools.Result r = logical ? CsvTools.exportRelationsLogical(model) : CsvTools.exportRelations(model);
            write(dst, r.data);
            return withOutput(r.log, dst);
        }

        static List<String> importNames(Path a5er, Path csv, Path out) throws ToolException {
            Path dst = out != null ? out : withSuffix(a5er, "names", ".a5er");
            checkOutput(dst, List.of(a5er, csv));
            CsvTools.Result r = CsvTools.importNames(CsvTools.loadModel(read(a5er)), read(csv));
            write(dst, r.data);
            return withOutput(r.log, dst);
        }

        static List<String> importRelations(Path a5er, Path csv, Path out) throws ToolException {
            return importRelations(a5er, csv, out, false);
        }

        static List<String> importRelations(Path a5er, Path csv, Path out, boolean logicalIfNoHeader) throws ToolException {
            Path dst = out != null ? out : withSuffix(a5er, "relations", ".a5er");
            checkOutput(dst, List.of(a5er, csv));
            CsvTools.Result r = CsvTools.importRelations(CsvTools.loadModel(read(a5er)), read(csv), logicalIfNoHeader);
            write(dst, r.data);
            return withOutput(r.log, dst);
        }

        static List<String> exportComments(Path a5er, Path out) throws ToolException {
            Path dst = out != null ? out : withSuffix(a5er, "comments", ".csv");
            checkOutput(dst, List.of(a5er));
            CsvTools.Result r = CsvTools.exportComments(CsvTools.loadModel(read(a5er)));
            write(dst, r.data);
            return withOutput(r.log, dst);
        }

        static List<String> importComments(Path a5er, Path csv, Path out) throws ToolException {
            Path dst = out != null ? out : withSuffix(a5er, "comments", ".a5er");
            checkOutput(dst, List.of(a5er, csv));
            CsvTools.Result r = CsvTools.importComments(CsvTools.loadModel(read(a5er)), read(csv));
            write(dst, r.data);
            return withOutput(r.log, dst);
        }

        static List<String> all(Path a5er, Path names, Path comments, Path relations, Path out, Options opt) throws ToolException {
            Path dst = out != null ? out : withSuffix(a5er, "all", ".a5er");
            List<Path> inputs = new ArrayList<>(Arrays.asList(a5er, names, comments, relations));
            checkOutput(dst, inputs);
            CsvTools.Result r = CsvTools.runAll(read(a5er), names == null ? null : read(names),
                    comments == null ? null : read(comments), relations == null ? null : read(relations), opt);
            write(dst, r.data);
            return withOutput(r.log, dst);
        }

        private static List<String> withOutput(List<String> log, Path dst) {
            List<String> out = new ArrayList<>(log);
            out.add("出力: " + dst);
            return out;
        }
    }

    // =====================================================================
    // コマンド
    // =====================================================================

    static final class Cli {
        private Cli() {
        }

        static final String USAGE = String.join("\n",
            "使い方（引数なしで起動すると画面が開きます）:",
            "  java -jar a5er-tool.jar layout           入力.a5er [-o 出力] [--mode compact|layered|grid] [--keep-lines]",
            "  java -jar a5er-tool.jar export-names     入力.a5er [-o 出力.csv]",
            "  java -jar a5er-tool.jar import-names     入力.a5er 論理名.csv [-o 出力.a5er]",
            "  java -jar a5er-tool.jar export-comments  入力.a5er [-o 出力.csv]",
            "  java -jar a5er-tool.jar import-comments  入力.a5er コメント.csv [-o 出力.a5er]",
            "  java -jar a5er-tool.jar export-relations 入力.a5er [-o 出力.csv] [--logical]",
            "  java -jar a5er-tool.jar import-relations 入力.a5er リレーション.csv [-o 出力.a5er] [--logical]",
            "  java -jar a5er-tool.jar all              入力.a5er [CSV ...] [-o 出力.a5er] [--mode ...] [--keep-lines] [--logical]",
            "  --logical: リレーション CSV を論理名で書き出す／ヘッダの無いリレーション CSV を論理名として読む",
            "  整列の細かい指定: --gap-x 60 --gap-y 100 --max-width 3000 --margin 50");

        static int run(String[] args) {
            try {
                List<String> pos = new ArrayList<>();
                Path out = null;
                Options opt = new Options();
                for (int i = 1; i < args.length; i++) {
                    String a = args[i];
                    switch (a) {
                        case "-o": case "--output": out = Paths.get(args[++i]); break;
                        case "--mode": opt.mode = args[++i]; break;
                        case "--keep-lines": opt.keepLines = true; break;
                        case "--logical": opt.logicalRelations = true; break;
                        case "--gap-x": opt.gapX = Integer.parseInt(args[++i]); break;
                        case "--gap-y": opt.gapY = Integer.parseInt(args[++i]); break;
                        case "--max-width": opt.maxWidth = Integer.parseInt(args[++i]); break;
                        case "--margin": opt.margin = Integer.parseInt(args[++i]); break;
                        default: pos.add(a); break;
                    }
                }
                if (!Set.of("compact", "layered", "grid").contains(opt.mode)) {
                    throw new ToolException("--mode は compact / layered / grid のどれかです。");
                }
                List<Path> paths = new ArrayList<>();
                for (String p : pos) {
                    Path path = Paths.get(p);
                    if (!Files.isRegularFile(path)) {
                        throw new ToolException("ファイルがありません: " + p);
                    }
                    paths.add(path);
                }
                List<String> log;
                switch (args[0]) {
                    case "layout": need(paths, 1); log = Actions.layout(paths.get(0), out, opt); break;
                    case "export-names": need(paths, 1); log = Actions.exportNames(paths.get(0), out); break;
                    case "export-relations": need(paths, 1); log = Actions.exportRelations(paths.get(0), out, opt.logicalRelations); break;
                    case "import-names": need(paths, 2); log = Actions.importNames(paths.get(0), paths.get(1), out); break;
                    case "export-comments": need(paths, 1); log = Actions.exportComments(paths.get(0), out); break;
                    case "import-comments": need(paths, 2); log = Actions.importComments(paths.get(0), paths.get(1), out); break;
                    case "import-relations": need(paths, 2);
                        log = Actions.importRelations(paths.get(0), paths.get(1), out, opt.logicalRelations); break;
                    case "all": {
                        if (paths.isEmpty()) {
                            throw new ToolException(USAGE);
                        }
                        Map<String, Path> byKind = new HashMap<>();
                        Map<String, String> labels = Map.of("names", "論理名", "comments", "コメント", "relations", "リレーション");
                        List<String> head = new ArrayList<>();
                        for (Path p : paths.subList(1, paths.size())) {
                            String kind = CsvTools.csvKind(read(p));
                            String label = labels.get(kind);
                            if (byKind.containsKey(kind)) {
                                throw new ToolException(label + "の CSV が2つ以上あります: " + p);
                            }
                            byKind.put(kind, p);
                            head.add(p.getFileName() + " → " + label + "の CSV");
                        }
                        log = new ArrayList<>(head);
                        log.addAll(Actions.all(paths.get(0), byKind.get("names"), byKind.get("comments"), byKind.get("relations"), out, opt));
                        break;
                    }
                    default: throw new ToolException(USAGE);
                }
                log.forEach(System.out::println);
                return 0;
            } catch (ToolException e) {
                System.err.println("エラー: " + e.getMessage());
                return 1;
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                System.err.println("エラー: 引数が正しくありません。\n" + USAGE);
                return 1;
            }
        }

        private static void need(List<Path> paths, int n) throws ToolException {
            if (paths.size() != n) {
                throw new ToolException(USAGE);
            }
        }
    }

    // =====================================================================
    // 画面
    // =====================================================================

    static final class MainWindow {
        private final JFrame frame = new JFrame("A5:SQL Mk-2 ER図ツール");
        private final JTextField a5erField = new JTextField(40);
        private final JTextField namesField = new JTextField(40);
        private final JTextField commentsField = new JTextField(40);
        private final JTextField relationsField = new JTextField(40);
        private final JComboBox<String> modeBox = new JComboBox<>(new String[] {"compact", "layered", "grid"});
        private final JCheckBox keepLines = new JCheckBox("線は変えない（テーブルの位置だけ整列）");
        private final JCheckBox logicalRels = new JCheckBox("リレーション CSV は論理名");
        private final JTextArea log = new JTextArea(16, 70);
        private final List<JButton> buttons = new ArrayList<>();
        private File lastDir;

        static void open() {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // 既定の見た目のまま
            }
            new MainWindow().show();
        }

        private void show() {
            JPanel files = new JPanel(new GridBagLayout());
            files.setBorder(BorderFactory.createTitledBorder("ファイル"));
            addFileRow(files, 0, ".a5er ファイル", a5erField, "a5er");
            addFileRow(files, 1, "論理名 CSV（任意）", namesField, "csv");
            addFileRow(files, 2, "コメント CSV（任意）", commentsField, "csv");
            addFileRow(files, 3, "リレーション CSV（任意）", relationsField, "csv");

            JPanel opts = new JPanel();
            opts.add(new JLabel("並べ方"));
            modeBox.setPrototypeDisplayValue("compact（線が重ならない）");
            modeBox.setToolTipText("compact: 線が重ならないよう正方形に近くまとめる / layered: 親を上・子を下 / grid: 名前順の格子");
            opts.add(modeBox);
            opts.add(keepLines);
            logicalRels.setToolTipText("書き出しを論理名にする。読み込みは CSV のヘッダで自動判定（ヘッダが無いときだけこのチェックに従う）");
            opts.add(logicalRels);

            JPanel acts = new JPanel(new GridLayout(3, 3, 6, 6));
            acts.setBorder(BorderFactory.createTitledBorder("実行（出力は .a5er と同じフォルダに別名で作ります）"));
            acts.add(button("論理名CSVを書き出す", () -> Actions.exportNames(a5er(), null)));
            acts.add(button("コメントCSVを書き出す", () -> Actions.exportComments(a5er(), null)));
            acts.add(button("リレーションCSVを書き出す", () -> Actions.exportRelations(a5er(), null, logicalRels.isSelected())));
            acts.add(button("論理名を反映", () -> Actions.importNames(a5er(), csv(namesField, "論理名 CSV"), null)));
            acts.add(button("コメントを反映", () -> Actions.importComments(a5er(), csv(commentsField, "コメント CSV"), null)));
            acts.add(button("リレーションを追加", () -> Actions.importRelations(a5er(), csv(relationsField, "リレーション CSV"), null,
                logicalRels.isSelected())));
            acts.add(button("整列", () -> Actions.layout(a5er(), null, options())));
            acts.add(button("一括（論理名→コメント→リレーション→整列）", () -> Actions.all(a5er(), optionalCsv(namesField),
                optionalCsv(commentsField), optionalCsv(relationsField), null, options())));

            log.setEditable(false);
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            log.setText("1. .a5er ファイルを選ぶ  2. 必要なら CSV を選ぶ  3. ボタンを押す\n\n");

            JPanel top = new JPanel(new BorderLayout());
            top.add(files, BorderLayout.NORTH);
            top.add(opts, BorderLayout.CENTER);
            top.add(acts, BorderLayout.SOUTH);

            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().add(top, BorderLayout.NORTH);
            frame.getContentPane().add(new JScrollPane(log), BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        private void addFileRow(JPanel panel, int row, String label, JTextField field, String ext) {
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3, 4, 3, 4);
            c.gridy = row;
            c.gridx = 0;
            c.anchor = GridBagConstraints.WEST;
            panel.add(new JLabel(label), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            panel.add(field, c);
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            JButton choose = new JButton("選ぶ");
            choose.addActionListener(e -> choose(field, ext));
            panel.add(choose, c);
            c.gridx = 3;
            JButton clear = new JButton("消す");
            clear.addActionListener(e -> field.setText(""));
            panel.add(clear, c);
        }

        private void choose(JTextField field, String ext) {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setFileFilter(new FileNameExtensionFilter("." + ext + " ファイル", ext));
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                lastDir = f.getParentFile();
                field.setText(f.getAbsolutePath());
            }
        }

        /** 処理を受け取ってボタンを作る。 */
        interface Task {
            List<String> run() throws ToolException;
        }

        private JButton button(String label, Task task) {
            JButton b = new JButton(label);
            b.addActionListener(e -> start(label, task));
            buttons.add(b);
            return b;
        }

        private void start(String label, Task task) {
            buttons.forEach(b -> b.setEnabled(false));
            append("▶ " + label + " ...");
            new SwingWorker<List<String>, Void>() {
                private String error;

                @Override
                protected List<String> doInBackground() {
                    try {
                        return task.run();
                    } catch (ToolException e) {
                        error = e.getMessage();
                    } catch (RuntimeException e) {
                        error = "思わぬエラー: " + e;
                    }
                    return List.of();
                }

                @Override
                protected void done() {
                    try {
                        List<String> lines = get();
                        if (error != null) {
                            append("エラー: " + error);
                        } else {
                            lines.forEach(MainWindow.this::append);
                        }
                    } catch (Exception e) {
                        append("エラー: " + e);
                    }
                    append("");
                    buttons.forEach(b -> b.setEnabled(true));
                }
            }.execute();
        }

        private void append(String line) {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        }

        private Options options() {
            Options o = new Options();
            o.mode = (String) modeBox.getSelectedItem();
            o.keepLines = keepLines.isSelected();
            o.logicalRelations = logicalRels.isSelected();
            return o;
        }

        private Path a5er() throws ToolException {
            String s = a5erField.getText().strip();
            if (s.isEmpty()) {
                throw new ToolException(".a5er ファイルを選んでください。");
            }
            return existing(s);
        }

        private Path csv(JTextField field, String label) throws ToolException {
            String s = field.getText().strip();
            if (s.isEmpty()) {
                throw new ToolException(label + "を選んでください。");
            }
            return existing(s);
        }

        private Path optionalCsv(JTextField field) throws ToolException {
            String s = field.getText().strip();
            return s.isEmpty() ? null : existing(s);
        }

        private static Path existing(String s) throws ToolException {
            Path p = Paths.get(s);
            if (!Files.isRegularFile(p)) {
                throw new ToolException("ファイルがありません: " + s);
            }
            return p;
        }
    }
}
