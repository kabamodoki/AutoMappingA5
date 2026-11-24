package a5er;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import a5er.A5erTool.CsvTools;
import a5er.A5erTool.Document;
import a5er.A5erTool.Layout;
import a5er.A5erTool.Options;
import a5er.A5erTool.Placement;
import a5er.A5erTool.Relation;
import a5er.A5erTool.Router;
import a5er.A5erTool.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class A5erToolTest {

    // ------------------------------------------------------------------ 試験用データ

    static final String HEADER = "# A5:ER FORMAT:16\n# A5:ER ENCODING:UTF8\n# A5:ER Mk-1 Copyright © 2007 m.matsubara\n\n"
            + "[Manager]\nProjectName=test\nPage=Main\nPageInfo=\"Main\",2,\"A3Landscape\",$FFFFFF\n"
            + "PageInfo=\"Sub\",2,\"A3Landscape\",$FFFFFF\n\n";

    static String entity(String name, String page, int fields, boolean size) {
        StringBuilder sb = new StringBuilder("[Entity]\nPName=" + name + "\nLName=" + name + "の論理名\nComment=\nTableOption=\n"
                + "Page=" + page + "\nLeft=0\nTop=0\n");
        for (int i = 0; i < fields; i++) {
            sb.append("Field=\"列").append(i).append("\",\"col").append(i).append("\",\"@VARCHAR\",\"NOT NULL\",,\"\",\"\",$FFFFFFFF,\"\"\n");
        }
        sb.append("EffectMode=None\nColor=$000000\nBkColor=$FFFFFF\n");
        sb.append(size ? "Position=\"" + page + "\",100,100,200," + (40 + fields * 16) : "Position=\"" + page + "\",100,100");
        return sb.append("\nZOrder=1\n\n").toString();
    }

    static String entity(String name) {
        return entity(name, "MAIN", 2, true);
    }

    static String relation(String parent, String child) {
        return "[Relation]\nEntity1=" + parent + "\nEntity2=" + child + "\nRelationType1=2\nRelationType2=3\n"
                + "Fields1=col0\nFields2=col1\nCardinarity1=\nCardinarity2=\n"
                + "Position=\"MAIN\",0,2048,7952,2089,R,R,\"\"\n"
                + "Dependence=0\nCaption=\nPName=\nLineMode=0\nBar1=205\nBar2=795\nBar3=209\n"
                + "TermPos1=R\nTermPos2=R\nZOrder=5\n\n";
    }

    static byte[] sample(boolean crlf, boolean bom, boolean size) {
        StringBuilder body = new StringBuilder(HEADER);
        for (String n : List.of("users", "orders", "order_items", "products", "categories", "logs", "settings")) {
            body.append(entity(n, "MAIN", 2, size));
        }
        body.append(entity("sub_a", "Sub", 2, size)).append(entity("sub_b", "Sub", 2, size));
        body.append(relation("users", "orders")).append(relation("orders", "order_items"))
            .append(relation("products", "order_items")).append(relation("categories", "products"))
            .append(relation("sub_a", "sub_b"));
        String s = crlf ? body.toString().replace("\n", "\r\n") : body.toString();
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        if (!bom) {
            return data;
        }
        byte[] all = new byte[data.length + 3];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(data, 0, all, 3, data.length);
        return all;
    }

    static byte[] sample() {
        return sample(false, false, true);
    }

    static byte[] csv(String enc, String... lines) {
        String text = String.join("\r\n", lines) + "\r\n";
        if (enc.equals("MS932")) {
            return text.getBytes(A5erTool.MS932);
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String text(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    static byte[] layout(byte[] data, String mode, boolean keepLines) throws Exception {
        Options o = new Options();
        o.mode = mode;
        o.keepLines = keepLines;
        return Layout.layoutBytes(data, o).data;
    }

    /** sample-db と同じ 20 テーブル（リレーション無し、Position に大きさ無し）＋ 22 本のリレーション。 */
    static byte[] schemaWithRelations() throws Exception {
        String sql = Files.readString(Paths.get("sample-db", "init.sql"));
        StringBuilder body = new StringBuilder(HEADER);
        Matcher m = Pattern.compile("CREATE TABLE (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL).matcher(sql);
        while (m.find()) {
            body.append("[Entity]\nPName=").append(m.group(1)).append("\nLName=").append(m.group(1)).append("\nPage=Main\nLeft=0\nTop=0\n");
            for (String ln : m.group(2).strip().split("\n")) {
                if (!ln.strip().startsWith("PRIMARY")) {
                    String c = ln.strip().split("\\s+")[0];
                    body.append("Field=\"").append(c).append("\",\"").append(c).append("\",\"@INT\",\"\",,\"\",\"\",$FFFFFFFF,\"\"\n");
                }
            }
            body.append("Position=\"Main\",0,0\nZOrder=1\n\n");
        }
        CsvTools.Result r = CsvTools.importRelations(CsvTools.loadModel(body.toString().getBytes(StandardCharsets.UTF_8)),
                Files.readAllBytes(Paths.get("sample-db", "sample_relations.csv")));
        assertEquals(22, CsvTools.lastReport.done);
        return r.data;
    }

    /** テーブル名 → [x, y, w, h]（Position の値と推定の大きさ）。 */
    static Map<String, int[]> boxes(byte[] data) {
        Document doc = A5erTool.parseDocument(data);
        Map<String, int[]> out = new HashMap<>();
        for (Table t : doc.tables) {
            Placement p = t.placements.get(0);
            String[] pos = A5erTool.parsePosition(A5erTool.valueOf(doc.lines().get(p.posIdx)));
            out.put(t.name(), new int[] {Integer.parseInt(pos[1]), Integer.parseInt(pos[2]), p.w, p.h});
        }
        return out;
    }

    /**
     * 出力を読み直し、A5:SQL Mk-2 の線の決まりで線を描いて確かめる。
     * テーブル（帯を含む）が重ならない・線がほかのテーブルにかからない・線どうしが重ならない。
     * src から変わっていない線（線を引けず元のまま残したもの）は調べない。調べた線の数を返す。
     */
    static int checkLayout(byte[] data, byte[] src) {
        Document doc = A5erTool.parseDocument(data);
        List<String> srcLines = A5erTool.parseDocument(src).lines();
        double k = doc.fontSize / 6;
        Set<String> loops = new TreeSet<>();
        for (Relation r : doc.relations) {
            if (r.parent.equals(r.child)) {
                loops.add(r.parent);
            }
        }
        Map<String, Map<String, Router.Box>> byPage = new HashMap<>();
        for (Table t : doc.tables) {
            for (Placement p : t.placements) {
                String[] pos = A5erTool.parsePosition(A5erTool.valueOf(doc.lines().get(p.posIdx)));
                p.x = Integer.parseInt(pos[1]);
                p.y = Integer.parseInt(pos[2]);
                byPage.computeIfAbsent(p.page, x -> new HashMap<>()).put(t.name(), Layout.toBox(p, k, loops.contains(t.name())));
            }
        }
        for (Map.Entry<String, Map<String, Router.Box>> page : byPage.entrySet()) {
            List<Map.Entry<String, Router.Box>> items = new ArrayList<>(page.getValue().entrySet());
            for (int i = 0; i < items.size(); i++) {
                double[] o1 = items.get(i).getValue().occupied();
                for (int j = i + 1; j < items.size(); j++) {
                    double[] o2 = items.get(j).getValue().occupied();
                    assertFalse(o1[0] < o2[2] && o2[0] < o1[2] && o1[1] < o2[3] && o2[1] < o1[3],
                            "テーブルの重なり " + items.get(i).getKey() + " " + items.get(j).getKey());
                }
            }
        }
        List<Object[]> lines = new ArrayList<>();
        for (Relation rel : doc.relations) {
            if (rel.parent.equals(rel.child)) {
                continue;
            }
            for (Object[] pl : rel.posLines) {
                int idx = (Integer) pl[0];
                Map<String, Router.Box> bx = byPage.getOrDefault((String) pl[1], Map.of());
                boolean unchanged = idx < srcLines.size() && srcLines.get(idx).equals(doc.lines().get(idx));
                if (!bx.containsKey(rel.parent) || !bx.containsKey(rel.child) || unchanged) {
                    continue;
                }
                String[] pos = A5erTool.parsePosition(A5erTool.valueOf(doc.lines().get(idx)));
                double[] bars = {Integer.parseInt(pos[2]) / 10000.0, Integer.parseInt(pos[3]) / 10000.0, Integer.parseInt(pos[4]) / 10000.0};
                Router.Box p = bx.get(rel.parent);
                Router.Box c = bx.get(rel.child);
                char mode = Router.direction(p, c);
                assertNotEquals(0, mode, "向きがあいまい " + rel.parent + " → " + rel.child);
                Router.Line line = Router.toLine(Router.linePoints(p, c, bars, mode));
                for (Map.Entry<String, Router.Box> e : bx.entrySet()) {
                    if (e.getKey().equals(rel.parent) || e.getKey().equals(rel.child)) {
                        continue;
                    }
                    for (double[] s : line.segments) {
                        assertFalse(Router.segHitsRect(s, e.getValue().occupied(), 0),
                                "線がテーブルにかかる " + rel.parent + " → " + rel.child + " / " + e.getKey());
                    }
                }
                lines.add(new Object[] {pl[1], rel, line});
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                if (lines.get(i)[0].equals(lines.get(j)[0])) {
                    Relation a = (Relation) lines.get(i)[1];
                    Relation b = (Relation) lines.get(j)[1];
                    assertTrue(Router.compare((Router.Line) lines.get(i)[2], (Router.Line) lines.get(j)[2]) >= 0,
                            "線の重なり " + a.parent + "→" + a.child + " / " + b.parent + "→" + b.child);
                }
            }
        }
        return lines.size();
    }

    static final Pattern POS_LINE = Pattern.compile("^(Left|Top|Position|Bar[123])=");

    // ------------------------------------------------------------------ 整列

    @Test
    void onlyPositionAndLineRowsChange() throws Exception {
        for (String mode : List.of("compact", "layered")) {
            for (boolean crlf : List.of(false, true)) {
                for (boolean bom : List.of(false, true)) {
                    byte[] src = sample(crlf, bom, true);
                    byte[] out = layout(src, mode, false);
                    assertEquals(bom, A5erTool.startsWithBom(out));
                    String[] a = text(src).split("\n", -1);
                    String[] b = text(out).split("\n", -1);
                    assertEquals(a.length, b.length);
                    int changed = 0;
                    for (int i = 0; i < a.length; i++) {
                        if (!a[i].equals(b[i])) {
                            assertTrue(POS_LINE.matcher(a[i]).find(), a[i]);
                            assertEquals(a[i].endsWith("\r"), b[i].endsWith("\r"));
                            changed++;
                        }
                    }
                    assertTrue(changed > 0);
                }
            }
        }
    }

    @Test
    void layeredPutsParentAboveChild() throws Exception {
        Map<String, int[]> b = boxes(layout(sample(), "layered", false));
        for (String[] pc : List.of(new String[] {"users", "orders"}, new String[] {"orders", "order_items"},
                new String[] {"products", "order_items"}, new String[] {"categories", "products"}, new String[] {"sub_a", "sub_b"})) {
            assertTrue(b.get(pc[0])[1] < b.get(pc[1])[1], pc[0] + " → " + pc[1]);
        }
    }

    @Test
    void noOverlapInAllModes() throws Exception {
        for (String mode : List.of("compact", "layered", "grid")) {
            checkLayout(layout(sample(), mode, false), sample());
        }
    }

    @Test
    void pagesAreSeparate() throws Exception {
        Map<String, int[]> b = boxes(layout(sample(), "compact", false));
        assertEquals(50, Math.min(b.get("sub_a")[0], b.get("sub_b")[0]));
    }

    @Test
    void leftTopFollowPosition() throws Exception {
        String out = text(layout(sample(), "compact", false));
        for (String sec : out.split("\\[Entity\\]")) {
            Matcher left = Pattern.compile("(?m)^Left=(\\d+)").matcher(sec);
            if (!left.find()) {
                continue;
            }
            Matcher top = Pattern.compile("(?m)^Top=(\\d+)").matcher(sec);
            Matcher pos = Pattern.compile("(?m)^Position=\"[^\"]*\",(\\d+),(\\d+)").matcher(sec);
            assertTrue(top.find() && pos.find());
            assertEquals(left.group(1), pos.group(1));
            assertEquals(top.group(1), pos.group(2));
        }
    }

    @Test
    void worksWithoutSizeInPosition() throws Exception {
        byte[] src = sample(false, false, false);
        byte[] out = layout(src, "compact", false);
        assertEquals(4, checkLayout(out, src));
    }

    @Test
    void cycleIsOk() throws Exception {
        byte[] data = (HEADER + entity("a") + entity("b") + entity("c") + relation("a", "b") + relation("b", "c")
                + relation("c", "a")).getBytes(StandardCharsets.UTF_8);
        for (String mode : List.of("compact", "layered")) {
            checkLayout(layout(data, mode, false), data);
        }
    }

    @Test
    void stable() throws Exception {
        for (String mode : List.of("compact", "layered")) {
            byte[] once = layout(sample(), mode, false);
            assertEquals(text(once), text(layout(once, mode, false)));
        }
    }

    @Test
    void noEntityIsError() {
        assertThrows(A5erTool.ToolException.class, () -> layout(HEADER.getBytes(StandardCharsets.UTF_8), "compact", false));
    }

    @Test
    void keepLines() throws Exception {
        String out = text(layout(sample(), "compact", true));
        assertEquals(5, out.split("Position=\"MAIN\",0,2048,7952,2089,R,R,\"\"", -1).length - 1);
    }

    @Test
    void linesAreRoutedAndBarsMatchPosition() throws Exception {
        String out = text(layout(sample(), "compact", false));
        // sub_a→sub_b は Position が別ページ（MAIN）なので変えない
        assertEquals(1, out.split("Position=\"MAIN\",0,2048,7952,2089,R,R,\"\"", -1).length - 1);
        String[] secs = out.split("\\[Relation\\]");
        for (int s = 1; s < secs.length; s++) {
            Matcher pos = Pattern.compile("(?m)^Position=\"MAIN\",0,(\\d+),(\\d+),(\\d+),R,R,\"\"").matcher(secs[s]);
            assertTrue(pos.find());
            for (int i = 1; i <= 3; i++) {
                Matcher bar = Pattern.compile("(?m)^Bar" + i + "=(\\d+)").matcher(secs[s]);
                assertTrue(bar.find());
                assertEquals(A5erTool.rnd(Integer.parseInt(pos.group(i)) / 10.0), Integer.parseInt(bar.group(1)));
            }
        }
    }

    @Test
    void routeModelMatchesA5m2() {
        // localtest/calibration.a5er を A5:SQL Mk-2 で開いて測った結果
        assertEquals('v', Router.direction(box(100, 100, 3), box(600, 700, 3)));
        assertEquals('v', Router.direction(box(1200, 700, 3), box(1700, 100, 3)));
        assertEquals('h', Router.direction(box(100, 1200, 12), box(700, 1300, 3)));
        assertEquals('h', Router.direction(box(1800, 1200, 12), box(1200, 1300, 3)));
        assertEquals('h', Router.direction(box(100, 1800, 3), box(1000, 1950, 3)));
        double[] bars = {0.2, 0.3, 0.8};
        Router.Box dp = box(1800, 1200, 12);
        Router.Box dc = box(1200, 1300, 3);
        double x = Router.linePoints(dp, dc, bars, 'h')[1][0];
        assertEquals(dc.right() + 0.3 * (dp.x - dc.right()), x, 1e-9); // 左のテーブル（子）から数える
    }

    static Router.Box box(double x, double y, int rows) {
        return new Router.Box(x, y, 140, rows * 26 + 8, 60, 26, 0, false);
    }

    @Test
    void compactSampleDbSchema() throws Exception {
        byte[] src = schemaWithRelations();
        A5erTool.LayoutResult r = Layout.layoutBytes(src, new Options());
        assertEquals(0, r.unrouted);
        assertTrue(checkLayout(r.data, src) >= 18);
        Map<String, int[]> b = boxes(r.data);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = 0;
        int maxY = 0;
        for (int[] v : b.values()) {
            minX = Math.min(minX, v[0]);
            minY = Math.min(minY, v[1]);
            maxX = Math.max(maxX, v[0] + v[2]);
            maxY = Math.max(maxY, v[1] + v[3]);
        }
        double w = maxX - minX;
        double h = maxY - minY;
        assertTrue(Math.max(w, h) / Math.min(w, h) < 2.0);
    }

    @Test
    void layeredSampleDbSchema() throws Exception {
        byte[] src = schemaWithRelations();
        checkLayout(layout(src, "layered", false), src);
    }

    @Test
    void randomGraphs() throws Exception {
        Random rnd = new Random(7);
        int[] sizes = {2, 3, 5, 12, 30};
        for (int trial = 0; trial < 3; trial++) {
            StringBuilder body = new StringBuilder(HEADER);
            for (int i = 0; i < 15; i++) {
                body.append(entity(String.format("t%02d", i), "MAIN", sizes[rnd.nextInt(sizes.length)], false));
            }
            Set<String> pairs = new TreeSet<>();
            while (pairs.size() < 18) {
                int a = rnd.nextInt(15);
                int b = rnd.nextInt(15);
                if (a != b) {
                    pairs.add(String.format("t%02d,t%02d", a, b));
                }
            }
            for (String p : pairs) {
                String[] ab = p.split(",");
                body.append(relation(ab[0], ab[1]));
            }
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            A5erTool.LayoutResult r = Layout.layoutBytes(data, new Options());
            assertTrue(r.unrouted <= 1);
            checkLayout(r.data, data);
        }
    }

    // ------------------------------------------------------------------ 論理名

    @Test
    void namesRoundTripIsIdentical() throws Exception {
        for (boolean crlf : List.of(false, true)) {
            byte[] src = sample(crlf, crlf, true);
            CsvTools.Result exported = CsvTools.exportNames(CsvTools.loadModel(src));
            assertTrue(A5erTool.startsWithBom(exported.data));
            CsvTools.Result out = CsvTools.importNames(CsvTools.loadModel(src), exported.data);
            assertEquals(text(src), text(out.data));
            assertEquals(A5erTool.startsWithBom(src), A5erTool.startsWithBom(out.data));
            assertEquals(0, CsvTools.lastReport.done);
            assertTrue(CsvTools.lastReport.skipped.isEmpty());
        }
    }

    @Test
    void namesExportFormat() throws Exception {
        String out = text(CsvTools.exportNames(CsvTools.loadModel(sample())).data);
        String[] rows = out.split("\r\n");
        assertEquals("テーブル物理名,カラム物理名,論理名", rows[0]);
        assertEquals("users,,usersの論理名", rows[1]);
        assertEquals("users,col0,列0", rows[2]);
    }

    @Test
    void sameColumnNameInDifferentTables() throws Exception {
        byte[] data = csv("UTF-8", "テーブル物理名,カラム物理名,論理名", "users,col0,ユーザーID", "ORDERS,COL0,注文ID", "users,,ユーザー");
        CsvTools.Result r = CsvTools.importNames(CsvTools.loadModel(sample()), data);
        assertEquals(3, CsvTools.lastReport.done);
        CsvTools.Model m = CsvTools.loadModel(r.data);
        assertEquals("ユーザー", m.findEntities("users").get(0).lname);
        assertEquals("ユーザーID", m.findEntities("users").get(0).findColumn("col0").logical);
        assertEquals("注文ID", m.findEntities("orders").get(0).findColumn("col0").logical);
        assertEquals("列0", m.findEntities("products").get(0).findColumn("col0").logical);
        assertTrue(text(r.data).contains("Field=\"ユーザーID\",\"col0\",\"@VARCHAR\",\"NOT NULL\",,\"\",\"\",$FFFFFFFF,\"\"\n"));
    }

    @Test
    void namesSkipEmptyAndQuote() throws Exception {
        byte[] data = csv("UTF-8", "users,nothing,x", "nope,col0,y", "users,col1,", "users,col1,\"A\"\"B\"");
        CsvTools.Result r = CsvTools.importNames(CsvTools.loadModel(sample()), data);
        assertEquals(1, CsvTools.lastReport.done);
        assertEquals(2, CsvTools.lastReport.skipped.size());
        assertTrue(CsvTools.lastReport.skipped.get(0).contains("1 行目"));
        assertTrue(text(r.data).contains("Field=\"A\"\"B\",\"col1\""));
        assertEquals("A\"B", CsvTools.loadModel(r.data).findEntities("users").get(0).findColumn("col1").logical);
    }

    @Test
    void missingLNameLineIsAdded() throws Exception {
        byte[] src = (HEADER + entity("t1")).replace("LName=t1の論理名\n", "").getBytes(StandardCharsets.UTF_8);
        CsvTools.Result r = CsvTools.importNames(CsvTools.loadModel(src), csv("UTF-8", "t1,,テーブル1"));
        assertEquals(1, CsvTools.lastReport.done);
        assertTrue(text(r.data).contains("PName=t1\nLName=テーブル1\n"));
    }

    @Test
    void ms932Csv() throws Exception {
        CsvTools.Result r = CsvTools.importNames(CsvTools.loadModel(sample()), csv("MS932", "users,col0,利用者番号"));
        assertEquals(1, CsvTools.lastReport.done);
        assertTrue(text(r.data).contains("Field=\"利用者番号\""));
    }

    // ------------------------------------------------------------------ リレーション

    @Test
    void relationsRoundTripAddsNothing() throws Exception {
        byte[] src = sample(true, false, true);
        CsvTools.Result exported = CsvTools.exportRelations(CsvTools.loadModel(src));
        assertEquals("users,orders,col0,col1,2,3,0", text(exported.data).split("\r\n")[1]);
        CsvTools.Result out = CsvTools.importRelations(CsvTools.loadModel(src), exported.data);
        assertEquals(text(src), text(out.data));
        assertEquals(5, CsvTools.lastReport.unchanged);
    }

    @Test
    void relationsAddAndSkip() throws Exception {
        byte[] data = csv("UTF-8", "親テーブル,子テーブル,親カラム,子カラム,親側種別,子側種別,依存",
                "LOGS,settings,COL0,col1,,,", "logs,settings,col0,col1,,,", "logs,nope,col0,col1,,,",
                "logs,settings,colX,col1,,,", "logs,settings,\"col0,col1\",col1,,,", "users,logs,\"col0,col1\",\"col0,col1\",1,4,1");
        CsvTools.Result r = CsvTools.importRelations(CsvTools.loadModel(sample()), data);
        assertEquals(2, CsvTools.lastReport.done);
        assertEquals(1, CsvTools.lastReport.unchanged);
        assertEquals(3, CsvTools.lastReport.skipped.size());
        String t = text(r.data);
        assertTrue(t.startsWith(text(sample())));
        assertTrue(t.contains("[Relation]\nEntity1=logs\nEntity2=settings\nRelationType1=2\nRelationType2=3\nFields1=col0\nFields2=col1\n"));
        assertTrue(t.contains("Fields1=col0,col1\nFields2=col0,col1\n"));
        assertTrue(t.contains("Dependence=1\n"));
        assertTrue(t.contains("ZOrder=6\n") && t.contains("ZOrder=7\n"));
        assertFalse(t.contains("\n\n\n"));
        assertEquals(7, CsvTools.loadModel(r.data).relations.size());
    }

    @Test
    void relationsCrlfWithoutTrailingNewline() throws Exception {
        byte[] src = (HEADER + entity("a") + entity("b")).replaceAll("\\n+$", "").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        CsvTools.Result r = CsvTools.importRelations(CsvTools.loadModel(src), csv("UTF-8", "a,b,col0,col0"));
        assertEquals(1, CsvTools.lastReport.done);
        String t = text(r.data);
        assertFalse(t.replace("\r\n", "").contains("\n"));
        assertTrue(t.contains("ZOrder=1\r\n\r\n[Relation]\r\n"));
    }

    // ------------------------------------------------------------------ 一括・コマンド

    @Test
    void csvKind() throws Exception {
        assertEquals("names", CsvTools.csvKind(csv("UTF-8", "テーブル物理名,カラム物理名,論理名", "a,b,c")));
        assertEquals("relations", CsvTools.csvKind(csv("UTF-8", "親テーブル,子テーブル,親カラム,子カラム,親側種別,子側種別,依存")));
        assertEquals("names", CsvTools.csvKind(csv("MS932", "a,,x")));
        assertEquals("relations", CsvTools.csvKind(csv("UTF-8", "a,b,c,d")));
    }

    @Test
    void runAll() throws Exception {
        byte[] names = csv("UTF-8", "logs,col0,ログID", "nope,x,y");
        byte[] rels = csv("UTF-8", "親テーブル,子テーブル,親カラム,子カラム,親側種別,子側種別,依存", "logs,settings,col0,col1,,,");
        CsvTools.Result r = CsvTools.runAll(sample(), names, rels, new Options());
        String t = text(r.data);
        assertTrue(t.contains("Field=\"ログID\",\"col0\""));
        assertTrue(t.contains("Entity1=logs\nEntity2=settings\n"));
        assertTrue(r.log.get(0).startsWith("論理名を反映 1 件"));
        assertTrue(r.log.stream().anyMatch(l -> l.startsWith("リレーションを追加 1 件")));
        assertTrue(r.log.get(r.log.size() - 1).startsWith("整列しました"));
        assertEquals(5, checkLayout(r.data, sample())); // 追加した線も含めて 5 本（sub は別ページ）
    }

    @Test
    void commands(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("er.a5er");
        Files.write(src, sample());
        assertEquals(0, A5erTool.Cli.run(new String[] {"export-names", src.toString()}));
        assertEquals(0, A5erTool.Cli.run(new String[] {"export-relations", src.toString()}));
        assertEquals(0, A5erTool.Cli.run(new String[] {"import-names", src.toString(), dir.resolve("er_names.csv").toString()}));
        assertEquals(0, A5erTool.Cli.run(new String[] {"import-relations", src.toString(), dir.resolve("er_relations.csv").toString()}));
        assertEquals(0, A5erTool.Cli.run(new String[] {"layout", src.toString()}));
        assertEquals(0, A5erTool.Cli.run(new String[] {"all", src.toString(), dir.resolve("er_relations.csv").toString(),
            dir.resolve("er_names.csv").toString()}));
        for (String name : List.of("er_names.csv", "er_relations.csv", "er_names.a5er", "er_relations.a5er", "er_layout.a5er", "er_all.a5er")) {
            assertTrue(Files.isRegularFile(dir.resolve(name)), name);
        }
        assertEquals(1, A5erTool.Cli.run(new String[] {"layout", src.toString(), "-o", src.toString()}));
        assertEquals(1, A5erTool.Cli.run(new String[] {"all", src.toString(), dir.resolve("er_names.csv").toString(),
            dir.resolve("er_names.csv").toString()}));
        assertNotNull(A5erTool.Cli.USAGE);
    }
}
