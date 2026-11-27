package a5er;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Salesforce のオブジェクト定義から、A5erTool で読める CSV（論理名・リレーション・コメント）を作る補助ツール。
 * 定義はメタデータのフォルダ（SFDX 形式・メタデータ API 形式）か、組織の REST API（sf コマンドの別名、または URL ＋ トークン）から取る。
 * 外部ライブラリなし。引数なしで起動すると画面が開く。
 */
public final class SfToCsv {

    static final List<String> NAMES_HEADER = List.of("テーブル物理名", "カラム物理名", "論理名");
    static final List<String> RELATIONS_HEADER = List.of("親テーブル", "子テーブル", "親カラム", "子カラム", "親側種別", "子側種別", "依存");
    static final List<String> COMMENTS_HEADER = List.of("テーブル物理名", "カラム物理名", "コメント");
    static final String DEFAULT_API = "60.0";

    private SfToCsv() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            SwingUtilities.invokeLater(Window::open);
            return;
        }
        System.exit(Cli.run(args));
    }

    /** 利用者に見せるエラー。 */
    static final class SfException extends Exception {
        SfException(String message) {
            super(message);
        }
    }

    // =====================================================================
    // 定義のモデル
    // =====================================================================

    static final class Field {
        final String name;
        String label;
        String type;
        final List<String> referenceTo = new ArrayList<>();
        String description = "";

        Field(String name, String label, String type) {
            this.name = name;
            this.label = label;
            this.type = type;
        }

        boolean isReference() {
            String t = type.toLowerCase(Locale.ROOT);
            return !referenceTo.isEmpty()
                    && (t.equals("lookup") || t.equals("masterdetail") || t.equals("reference") || t.equals("hierarchy")
                        || t.equals("metadatarelationship") || t.equals("externallookup") || t.equals("indirectlookup"));
        }
    }

    static final class SObject {
        final String name;
        String label;
        String description = "";
        final Map<String, Field> fields = new LinkedHashMap<>();

        SObject(String name, String label) {
            this.name = name;
            this.label = label;
        }

        boolean isCustom() {
            return name.endsWith("__c");
        }
    }

    // =====================================================================
    // メタデータのフォルダを読む
    // =====================================================================

    static final class Metadata {
        private Metadata() {
        }

        static Map<String, SObject> read(Path dir) throws SfException {
            if (!Files.isDirectory(dir)) {
                throw new SfException("フォルダがありません: " + dir);
            }
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
            } catch (IOException e) {
                throw new SfException("フォルダを読めません: " + dir);
            }
            Map<String, SObject> objects = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Path f : files) {
                String fn = f.getFileName().toString();
                if (fn.endsWith(".object-meta.xml")) {
                    String name = fn.substring(0, fn.length() - ".object-meta.xml".length());
                    Element root = parse(f);
                    SObject o = object(objects, name);
                    applyObject(o, root);
                    for (Element fe : children(root, "fields")) { // まれに中に fields を持つ
                        applyField(o, fe);
                    }
                } else if (fn.endsWith(".field-meta.xml")) {
                    Path fieldsDir = f.getParent();
                    if (fieldsDir == null || !fieldsDir.getFileName().toString().equals("fields") || fieldsDir.getParent() == null) {
                        continue;
                    }
                    String objName = fieldsDir.getParent().getFileName().toString();
                    applyField(object(objects, objName), parse(f));
                } else if (fn.endsWith(".object")) {
                    String name = fn.substring(0, fn.length() - ".object".length());
                    Element root = parse(f);
                    SObject o = object(objects, name);
                    applyObject(o, root);
                    for (Element fe : children(root, "fields")) {
                        applyField(o, fe);
                    }
                }
            }
            if (objects.isEmpty()) {
                throw new SfException("オブジェクトの定義（*.object-meta.xml / *.object）が見つかりません: " + dir);
            }
            for (SObject o : objects.values()) {
                addStandardFields(o);
            }
            return objects;
        }

        private static SObject object(Map<String, SObject> objects, String name) {
            return objects.computeIfAbsent(name, n -> new SObject(n, ""));
        }

        private static void applyObject(SObject o, Element root) {
            String label = text(root, "label");
            if (!label.isEmpty()) {
                o.label = label;
            }
            String desc = text(root, "description");
            if (!desc.isEmpty()) {
                o.description = desc;
            }
            Element nameField = first(root, "nameField");
            if (nameField != null) {
                String nl = text(nameField, "label");
                Field f = o.fields.get("Name");
                if (f == null) {
                    o.fields.put("Name", new Field("Name", nl, "Name"));
                } else if (!nl.isEmpty()) {
                    f.label = nl;
                }
            }
        }

        private static void applyField(SObject o, Element e) {
            String name = text(e, "fullName");
            if (name.isEmpty()) {
                return;
            }
            Field f = o.fields.computeIfAbsent(name, n -> new Field(n, "", ""));
            String label = text(e, "label");
            if (!label.isEmpty()) {
                f.label = label;
            }
            String type = text(e, "type");
            if (!type.isEmpty()) {
                f.type = type;
            }
            for (Element r : children(e, "referenceTo")) {
                String t = r.getTextContent().strip();
                if (!t.isEmpty() && !f.referenceTo.contains(t)) {
                    f.referenceTo.add(t);
                }
            }
            String desc = text(e, "description");
            if (desc.isEmpty()) {
                desc = text(e, "inlineHelpText");
            }
            if (!desc.isEmpty()) {
                f.description = desc;
            }
        }

        /** メタデータには無い Id・Name を先頭に足す。 */
        static void addStandardFields(SObject o) {
            String base = o.label.isEmpty() ? o.name : o.label;
            Map<String, Field> rest = new LinkedHashMap<>(o.fields);
            o.fields.clear();
            o.fields.put("Id", rest.containsKey("Id") ? rest.remove("Id") : new Field("Id", base + " ID", "id"));
            Field name = rest.remove("Name");
            if (name == null) {
                name = new Field("Name", base + "名", "Name");
            } else if (name.label.isEmpty()) {
                name.label = base + "名";
            }
            o.fields.put("Name", name);
            o.fields.putAll(rest);
        }

        static Element parse(Path f) throws SfException {
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(false);
                dbf.setExpandEntityReferences(false);
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                return db.parse(f.toFile()).getDocumentElement();
            } catch (Exception e) {
                throw new SfException("XML を読めません: " + f + "（" + e.getMessage() + "）");
            }
        }

        /** 直下の子要素（名前の一致するもの）。 */
        static List<Element> children(Element e, String tag) {
            List<Element> out = new ArrayList<>();
            NodeList nl = e.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n instanceof Element && localName(n).equals(tag)) {
                    out.add((Element) n);
                }
            }
            return out;
        }

        static Element first(Element e, String tag) {
            List<Element> c = children(e, tag);
            return c.isEmpty() ? null : c.get(0);
        }

        static String text(Element e, String tag) {
            Element c = first(e, tag);
            return c == null ? "" : c.getTextContent().strip();
        }

        private static String localName(Node n) {
            String s = n.getNodeName();
            int colon = s.indexOf(':');
            return colon < 0 ? s : s.substring(colon + 1);
        }
    }

    // =====================================================================
    // 組織の REST API を読む
    // =====================================================================

    static final class Api {
        final String instanceUrl;
        final String token;
        final String version;
        private final HttpClient http = HttpClient.newHttpClient();

        Api(String instanceUrl, String token, String version) {
            this.instanceUrl = instanceUrl.replaceAll("/+$", "");
            this.token = token;
            this.version = version;
        }

        /** sf コマンド（Salesforce CLI）のログイン情報から作る。 */
        static Api fromOrg(String alias, String version) throws SfException {
            List<String> cmd = new ArrayList<>();
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                cmd.addAll(List.of("cmd", "/c"));
            }
            cmd.addAll(List.of("sf", "org", "display", "--target-org", alias, "--json"));
            String out;
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
                out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
                p.waitFor();
            } catch (IOException e) {
                throw new SfException("sf コマンドを実行できません。Salesforce CLI が入っているか確認してください。");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SfException("中断しました。");
            }
            Object json;
            try {
                json = Json.parse(out);
            } catch (SfException e) {
                throw new SfException("sf org display の結果を読めません: " + head(out));
            }
            Map<?, ?> result = asMap(asMap(json).get("result"));
            String url = str(result.get("instanceUrl"));
            String token = str(result.get("accessToken"));
            if (url.isEmpty() || token.isEmpty()) {
                String msg = str(asMap(json).get("message"));
                throw new SfException("組織「" + alias + "」の接続情報を取れません。sf org login で接続してください。"
                        + (msg.isEmpty() ? "" : "（" + msg + "）"));
            }
            return new Api(url, token, version);
        }

        Object get(String path) throws SfException {
            HttpRequest req = HttpRequest.newBuilder(URI.create(instanceUrl + "/services/data/v" + version + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> res;
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new SfException("接続できません: " + instanceUrl + "（" + e.getMessage() + "）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SfException("中断しました。");
            }
            if (res.statusCode() != 200) {
                throw new SfException("HTTP " + res.statusCode() + ": " + head(res.body()));
            }
            return Json.parse(res.body());
        }

        /** 対象オブジェクトの定義を取る。names が空ならカスタムオブジェクト全部。 */
        Map<String, SObject> read(List<String> names, List<String> log) throws SfException {
            Map<String, String> labels = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            List<String> targets = new ArrayList<>();
            for (Object o : asList(asMap(get("/sobjects/")).get("sobjects"))) {
                Map<?, ?> m = asMap(o);
                String name = str(m.get("name"));
                labels.put(name, str(m.get("label")));
                if (names.isEmpty() && Boolean.TRUE.equals(m.get("custom")) && name.endsWith("__c")) {
                    targets.add(name);
                }
            }
            if (!names.isEmpty()) {
                for (String n : names) {
                    if (labels.containsKey(n)) {
                        targets.add(n);
                    } else {
                        log.add("組織にないオブジェクト: " + n);
                    }
                }
            }
            Map<String, SObject> objects = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String n : targets) {
                objects.put(n, fromDescribe(get("/sobjects/" + URLEncoder.encode(n, StandardCharsets.UTF_8) + "/describe/")));
            }
            return objects;
        }

        static SObject fromDescribe(Object json) {
            Map<?, ?> m = asMap(json);
            SObject o = new SObject(str(m.get("name")), str(m.get("label")));
            for (Object fo : asList(m.get("fields"))) {
                Map<?, ?> fm = asMap(fo);
                Field f = new Field(str(fm.get("name")), str(fm.get("label")), str(fm.get("type")));
                for (Object r : asList(fm.get("referenceTo"))) {
                    f.referenceTo.add(str(r));
                }
                f.description = str(fm.get("inlineHelpText"));
                o.fields.put(f.name, f);
            }
            return o;
        }

        private static byte[] readAll(InputStream in) throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            in.transferTo(bo);
            return bo.toByteArray();
        }
    }

    static String head(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    static Map<?, ?> asMap(Object o) {
        return o instanceof Map ? (Map<?, ?>) o : Map.of();
    }

    static List<?> asList(Object o) {
        return o instanceof List ? (List<?>) o : List.of();
    }

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 小さな JSON の読み取り（オブジェクト → LinkedHashMap、配列 → ArrayList、数値 → Double）。 */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) throws SfException {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) {
                throw new SfException("JSON の形が正しくありません");
            }
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() throws SfException {
            if (i >= s.length()) {
                throw new SfException("JSON が途中で終わっています");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default: return number();
            }
        }

        private Object literal(String word, Object v) throws SfException {
            if (!s.startsWith(word, i)) {
                throw new SfException("JSON の形が正しくありません");
            }
            i += word.length();
            return v;
        }

        private Map<String, Object> object() throws SfException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> array() throws SfException {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        private void expect(char c) throws SfException {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new SfException("JSON の形が正しくありません");
            }
            i++;
        }

        private String string() throws SfException {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw new SfException("JSON の形が正しくありません");
                        }
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e); break;
                }
            }
            throw new SfException("JSON の文字列が閉じていません");
        }

        private Object number() throws SfException {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new SfException("JSON の形が正しくありません");
            }
        }
    }

    // =====================================================================
    // CSV を作る
    // =====================================================================

    static final class Options {
        List<String> objects = new ArrayList<>();
        boolean allRelations;
    }

    /** 作った 3 つの CSV と、表示する行。 */
    static final class Output {
        byte[] names;
        byte[] relations;
        byte[] comments;
        final List<String> log = new ArrayList<>();
    }

    static Output build(Map<String, SObject> all, Options opt, List<String> preLog) {
        Output out = new Output();
        out.log.addAll(preLog);
        // 対象
        Map<String, SObject> targets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (opt.objects.isEmpty()) {
            for (SObject o : all.values()) {
                if (o.isCustom()) {
                    targets.put(o.name, o);
                }
            }
        } else {
            for (String n : opt.objects) {
                SObject o = all.get(n);
                if (o == null) {
                    if (!preLog.contains("組織にないオブジェクト: " + n)) {
                        out.log.add("定義が見つからないオブジェクト: " + n);
                    }
                } else {
                    targets.put(o.name, o);
                }
            }
        }
        List<List<String>> names = new ArrayList<>();
        List<List<String>> rels = new ArrayList<>();
        List<List<String>> comments = new ArrayList<>();
        names.add(NAMES_HEADER);
        rels.add(RELATIONS_HEADER);
        comments.add(COMMENTS_HEADER);
        int fieldCount = 0;
        int skippedRels = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (SObject o : targets.values()) {
            names.add(List.of(o.name, "", o.label));
            if (!o.description.isEmpty()) {
                comments.add(List.of(o.name, "", o.description));
            }
            for (Field f : o.fields.values()) {
                fieldCount++;
                names.add(List.of(o.name, f.name, f.label));
                if (!f.description.isEmpty()) {
                    comments.add(List.of(o.name, f.name, f.description));
                }
                if (!f.isReference()) {
                    continue;
                }
                for (String parent : f.referenceTo) {
                    SObject p = targets.get(parent);
                    if (p == null && !opt.allRelations) {
                        skippedRels++;
                        continue;
                    }
                    String pName = p == null ? parent : p.name;
                    String key = (pName + "\u0000" + o.name + "\u0000" + f.name).toLowerCase(Locale.ROOT);
                    if (seen.add(key)) {
                        rels.add(List.of(pName, o.name, "Id", f.name, "", "", ""));
                    }
                }
            }
        }
        out.names = csv(names);
        out.relations = csv(rels);
        out.comments = csv(comments);
        out.log.add("オブジェクト " + targets.size() + " 個 / 項目 " + fieldCount + " 個 / リレーション " + (rels.size() - 1) + " 本"
                + " / コメント " + (comments.size() - 1) + " 行");
        if (skippedRels > 0) {
            out.log.add("対象外のオブジェクトへの参照 " + skippedRels + " 本は出していません（全部出すときは「対象外へのリレーションも出す」）");
        }
        return out;
    }

    /** BOM 付き UTF-8・CRLF の CSV。 */
    static byte[] csv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : row) {
                cells.add(c.matches("(?s).*[\",\r\n].*") ? "\"" + c.replace("\"", "\"\"") + "\"" : c);
            }
            sb.append(String.join(",", cells)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 3 つの CSV を outDir に書き、表示する行を返す。 */
    static List<String> write(Output out, Path outDir) throws SfException {
        try {
            Files.createDirectories(outDir);
            Path n = outDir.resolve("sf_names.csv");
            Path r = outDir.resolve("sf_relations.csv");
            Path c = outDir.resolve("sf_comments.csv");
            Files.write(n, out.names);
            Files.write(r, out.relations);
            Files.write(c, out.comments);
            List<String> log = new ArrayList<>(out.log);
            log.add("出力: " + n);
            log.add("出力: " + r);
            log.add("出力: " + c);
            return log;
        } catch (IOException e) {
            throw new SfException("ファイルを書けません（開いたままなら閉じてください）: " + outDir);
        }
    }

    static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("[,\\s]+")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    // =====================================================================
    // コマンド
    // =====================================================================

    static final class Cli {
        private Cli() {
        }

        static final String USAGE = String.join("\n",
            "使い方（引数なしで起動すると画面が開きます）:",
            "  java -cp a5er-tool.jar a5er.SfToCsv folder メタデータのフォルダ [オプション]",
            "  java -cp a5er-tool.jar a5er.SfToCsv org    sf の組織の別名      [オプション]",
            "  java -cp a5er-tool.jar a5er.SfToCsv api    インスタンスURL アクセストークン [オプション]",
            "オプション:",
            "  -o 出力フォルダ            （既定: 今のフォルダ）",
            "  --objects A__c,B__c,Account （既定: カスタムオブジェクト全部）",
            "  --all-relations           対象外のオブジェクトへの参照（User など）も出す",
            "  --api 60.0                REST API のバージョン",
            "出力: sf_names.csv（論理名）/ sf_relations.csv（リレーション）/ sf_comments.csv（コメント）");

        static int run(String[] args) {
            try {
                List<String> pos = new ArrayList<>();
                Options opt = new Options();
                Path outDir = Paths.get(".");
                String api = DEFAULT_API;
                for (int i = 1; i < args.length; i++) {
                    switch (args[i]) {
                        case "-o": outDir = Paths.get(args[++i]); break;
                        case "--objects": opt.objects = splitList(args[++i]); break;
                        case "--all-relations": opt.allRelations = true; break;
                        case "--api": api = args[++i]; break;
                        default: pos.add(args[i]); break;
                    }
                }
                List<String> pre = new ArrayList<>();
                Map<String, SObject> defs;
                switch (args[0]) {
                    case "folder": need(pos, 1); defs = Metadata.read(Paths.get(pos.get(0))); break;
                    case "org": need(pos, 1); defs = Api.fromOrg(pos.get(0), api).read(opt.objects, pre); break;
                    case "api": need(pos, 2); defs = new Api(pos.get(0), pos.get(1), api).read(opt.objects, pre); break;
                    default: throw new SfException(USAGE);
                }
                write(build(defs, opt, pre), outDir).forEach(System.out::println);
                return 0;
            } catch (SfException e) {
                System.err.println("エラー: " + e.getMessage());
                return 1;
            } catch (ArrayIndexOutOfBoundsException e) {
                System.err.println("エラー: 引数が正しくありません。\n" + USAGE);
                return 1;
            }
        }

        private static void need(List<String> pos, int n) throws SfException {
            if (pos.size() != n) {
                throw new SfException(USAGE);
            }
        }
    }

    // =====================================================================
    // 画面
    // =====================================================================

    static final class Window {
        private final JFrame frame = new JFrame("Salesforce 定義 → CSV（A5:SQL Mk-2 ER図ツール用）");
        private final JRadioButton byFolder = new JRadioButton("メタデータのフォルダ", true);
        private final JRadioButton byOrg = new JRadioButton("sf の組織の別名");
        private final JRadioButton byToken = new JRadioButton("URL ＋ アクセストークン");
        private final JTextField folder = new JTextField(40);
        private final JTextField alias = new JTextField(40);
        private final JTextField url = new JTextField(40);
        private final JPasswordField token = new JPasswordField(40);
        private final JTextField objects = new JTextField(40);
        private final JCheckBox allRelations = new JCheckBox("対象外のオブジェクトへのリレーションも出す（User など）");
        private final JTextField outDir = new JTextField(40);
        private final JTextArea log = new JTextArea(14, 70);
        private final JButton run = new JButton("CSV を作る");

        static void open() {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // 既定の見た目のまま
            }
            new Window().show();
        }

        private void show() {
            ButtonGroup g = new ButtonGroup();
            g.add(byFolder);
            g.add(byOrg);
            g.add(byToken);
            JPanel src = new JPanel(new GridBagLayout());
            src.setBorder(BorderFactory.createTitledBorder("定義の取り方"));
            row(src, 0, byFolder, folder, chooser(folder));
            row(src, 1, byOrg, alias, null);
            row(src, 2, byToken, url, null);
            row(src, 3, new JLabel("　　アクセストークン"), token, null);

            JPanel opts = new JPanel(new GridBagLayout());
            opts.setBorder(BorderFactory.createTitledBorder("対象と出力"));
            row(opts, 0, new JLabel("対象オブジェクト（空ならカスタム全部）"), objects, null);
            row(opts, 1, new JLabel(""), allRelations, null);
            row(opts, 2, new JLabel("出力フォルダ"), outDir, chooser(outDir));
            outDir.setText(new File(".").getAbsoluteFile().getParent());
            objects.setToolTipText("カンマ区切り。例: Invoice__c,Account");

            run.addActionListener(e -> start());
            JPanel btn = new JPanel();
            btn.add(run);

            log.setEditable(false);
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            log.setText("出力: sf_names.csv（論理名）/ sf_relations.csv（リレーション）/ sf_comments.csv（コメント）\n"
                    + "→ ER図ツールの「論理名を反映」「リレーションを追加」「コメントを反映」でそのまま読めます。\n\n");

            JPanel top = new JPanel(new BorderLayout());
            top.add(src, BorderLayout.NORTH);
            top.add(opts, BorderLayout.CENTER);
            top.add(btn, BorderLayout.SOUTH);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().add(top, BorderLayout.NORTH);
            frame.getContentPane().add(new JScrollPane(log), BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        private JButton chooser(JTextField field) {
            JButton b = new JButton("選ぶ");
            b.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(field.getText().isBlank() ? null : new File(field.getText()));
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    field.setText(fc.getSelectedFile().getAbsolutePath());
                }
            });
            return b;
        }

        private static void row(JPanel p, int y, java.awt.Component label, java.awt.Component field, JButton extra) {
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3, 4, 3, 4);
            c.gridy = y;
            c.gridx = 0;
            c.anchor = GridBagConstraints.WEST;
            p.add(label, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            p.add(field, c);
            if (extra != null) {
                c.gridx = 2;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                p.add(extra, c);
            }
        }

        private void start() {
            run.setEnabled(false);
            append("▶ CSV を作る ...");
            new SwingWorker<List<String>, Void>() {
                private String error;

                @Override
                protected List<String> doInBackground() {
                    try {
                        Options opt = new Options();
                        opt.objects = splitList(objects.getText());
                        opt.allRelations = allRelations.isSelected();
                        List<String> pre = new ArrayList<>();
                        Map<String, SObject> defs;
                        if (byFolder.isSelected()) {
                            if (folder.getText().isBlank()) {
                                throw new SfException("メタデータのフォルダを選んでください。");
                            }
                            defs = Metadata.read(Paths.get(folder.getText().strip()));
                        } else if (byOrg.isSelected()) {
                            if (alias.getText().isBlank()) {
                                throw new SfException("sf の組織の別名を入れてください（sf org list で確認できます）。");
                            }
                            defs = Api.fromOrg(alias.getText().strip(), DEFAULT_API).read(opt.objects, pre);
                        } else {
                            String t = new String(token.getPassword()).strip();
                            if (url.getText().isBlank() || t.isEmpty()) {
                                throw new SfException("インスタンス URL とアクセストークンを入れてください。");
                            }
                            defs = new Api(url.getText().strip(), t, DEFAULT_API).read(opt.objects, pre);
                        }
                        if (outDir.getText().isBlank()) {
                            throw new SfException("出力フォルダを選んでください。");
                        }
                        return write(build(defs, opt, pre), Paths.get(outDir.getText().strip()));
                    } catch (SfException e) {
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
                            lines.forEach(Window.this::append);
                        }
                    } catch (Exception e) {
                        append("エラー: " + e);
                    }
                    append("");
                    run.setEnabled(true);
                }
            }.execute();
        }

        private void append(String line) {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        }
    }
}
