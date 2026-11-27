package a5er;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import a5er.A5erTool.CsvTools;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SfToCsvTest {

    static final String NS = "xmlns=\"http://soap.sforce.com/2006/04/metadata\"";

    static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + s, StandardCharsets.UTF_8);
    }

    static String field(String name, String label, String type, String ref, String desc) {
        return "<CustomField " + NS + "><fullName>" + name + "</fullName><label>" + label + "</label><type>" + type + "</type>"
                + (ref == null ? "" : "<referenceTo>" + ref + "</referenceTo>")
                + (desc == null ? "" : "<description>" + desc + "</description>") + "</CustomField>";
    }

    /** SFDX 形式の Invoice__c と、メタデータ API 形式の Order__c。 */
    static Path metadata(Path dir) throws Exception {
        Path obj = dir.resolve("force-app/main/default/objects/Invoice__c");
        write(obj.resolve("Invoice__c.object-meta.xml"), "<CustomObject " + NS + "><label>請求</label><description>請求書, \"月次\"</description>"
                + "<nameField><label>請求番号</label><type>AutoNumber</type></nameField></CustomObject>");
        write(obj.resolve("fields/Account__c.field-meta.xml"), field("Account__c", "取引先", "Lookup", "Account", null));
        write(obj.resolve("fields/Order__c.field-meta.xml"), field("Order__c", "注文", "MasterDetail", "Order__c", null));
        write(obj.resolve("fields/Amount__c.field-meta.xml"), field("Amount__c", "金額", "Currency", null, "税込"));
        write(dir.resolve("mdapi/objects/Order__c.object"), "<CustomObject " + NS + "><label>注文</label>"
                + "<fields><fullName>Owner__c</fullName><label>担当</label><type>Lookup</type><referenceTo>User</referenceTo></fields>"
                + "<fields><fullName>No__c</fullName><label>番号</label><type>Text</type><inlineHelpText>手入力</inlineHelpText></fields>"
                + "</CustomObject>");
        return dir;
    }

    static String text(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    @Test
    void fromMetadataFolder(@TempDir Path dir) throws Exception {
        Map<String, SfToCsv.SObject> defs = SfToCsv.Metadata.read(metadata(dir));
        assertEquals(2, defs.size());
        assertEquals(List.of("Id", "Name", "Account__c", "Amount__c", "Order__c"), List.copyOf(defs.get("Invoice__c").fields.keySet()));
        SfToCsv.Output out = SfToCsv.build(defs, new SfToCsv.Options(), List.of());
        String names = text(out.names);
        assertTrue(names.startsWith("テーブル物理名,カラム物理名,論理名\r\nInvoice__c,,請求\r\nInvoice__c,Id,請求 ID\r\nInvoice__c,Name,請求番号\r\n"), names);
        assertTrue(names.contains("Order__c,Name,注文名\r\n"));
        assertTrue(names.contains("Invoice__c,Amount__c,金額\r\n"));
        String rels = text(out.relations);
        assertEquals("親テーブル,子テーブル,親カラム,子カラム,親側種別,子側種別,依存\r\nOrder__c,Invoice__c,Id,Order__c,,,\r\n", rels);
        String comments = text(out.comments);
        assertTrue(comments.contains("Invoice__c,,\"請求書, \"\"月次\"\"\"\r\n"), comments);
        assertTrue(comments.contains("Invoice__c,Amount__c,税込\r\n"));
        assertTrue(comments.contains("Order__c,No__c,手入力\r\n"));
        assertTrue(out.log.stream().anyMatch(l -> l.contains("対象外のオブジェクトへの参照 2 本")));

        SfToCsv.Options all = new SfToCsv.Options();
        all.allRelations = true;
        String r2 = text(SfToCsv.build(defs, all, List.of()).relations);
        assertTrue(r2.contains("Account,Invoice__c,Id,Account__c,,,\r\n"));
        assertTrue(r2.contains("User,Order__c,Id,Owner__c,,,\r\n"));

        SfToCsv.Options only = new SfToCsv.Options();
        only.objects = List.of("invoice__c", "Nope__c");
        SfToCsv.Output o3 = SfToCsv.build(defs, only, List.of());
        assertFalse(text(o3.names).contains("Order__c,,"));
        assertTrue(o3.log.contains("定義が見つからないオブジェクト: Nope__c"));
    }

    @Test
    void emptyFolderIsAnError(@TempDir Path dir) {
        assertThrows(SfToCsv.SfException.class, () -> SfToCsv.Metadata.read(dir));
    }

    @Test
    void fromDescribeJson() throws Exception {
        String json = "{\"name\":\"Task__c\",\"label\":\"作業\",\"fields\":["
                + "{\"name\":\"Id\",\"label\":\"作業 ID\",\"type\":\"id\",\"referenceTo\":[],\"inlineHelpText\":null},"
                + "{\"name\":\"Who__c\",\"label\":\"相手\",\"type\":\"reference\",\"referenceTo\":[\"Contact__c\",\"Lead__c\"],"
                + "\"inlineHelpText\":\"\\u62c5\\u5f53\\n\\\"者\\\"\"},"
                + "{\"name\":\"Size__c\",\"label\":\"大きさ\",\"type\":\"double\",\"referenceTo\":[],\"length\":18.0,\"nillable\":true}]}";
        SfToCsv.SObject task = SfToCsv.Api.fromDescribe(SfToCsv.Json.parse(json));
        assertEquals("作業", task.label);
        assertEquals(List.of("Contact__c", "Lead__c"), task.fields.get("Who__c").referenceTo);
        assertEquals("担当\n\"者\"", task.fields.get("Who__c").description);
        Map<String, SfToCsv.SObject> defs = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        defs.put("Task__c", task);
        defs.put("Contact__c", new SfToCsv.SObject("Contact__c", "連絡先"));
        SfToCsv.Output out = SfToCsv.build(defs, new SfToCsv.Options(), List.of());
        assertTrue(text(out.relations).contains("Contact__c,Task__c,Id,Who__c,,,\r\n"));
        assertFalse(text(out.relations).contains("Lead__c"));
        assertTrue(text(out.comments).contains("Task__c,Who__c,\"担当\n\"\"者\"\"\"\r\n"));
    }

    @Test
    void csvIsReadableByTheMainTool(@TempDir Path dir) throws Exception {
        SfToCsv.Output out = SfToCsv.build(SfToCsv.Metadata.read(metadata(dir)), new SfToCsv.Options(), List.of());
        // DB 側は小文字の名前でも照合できること
        String a5er = A5erToolTest.HEADER
                + "[Entity]\nPName=invoice__c\nLName=invoice__c\nComment=\nPage=MAIN\nLeft=0\nTop=0\n"
                + "Field=\"id\",\"id\",\"@VARCHAR\",\"NOT NULL\",0,\"\",\"\",$FFFFFFFF,\"\"\n"
                + "Field=\"order__c\",\"order__c\",\"@VARCHAR\",,,\"\",\"\",$FFFFFFFF,\"\"\n"
                + "Field=\"amount__c\",\"amount__c\",\"@INT\",,,\"\",\"\",$FFFFFFFF,\"\"\nPosition=\"MAIN\",0,0\nZOrder=1\n\n"
                + "[Entity]\nPName=order__c\nLName=order__c\nComment=\nPage=MAIN\nLeft=0\nTop=0\n"
                + "Field=\"id\",\"id\",\"@VARCHAR\",\"NOT NULL\",0,\"\",\"\",$FFFFFFFF,\"\"\nPosition=\"MAIN\",0,0\nZOrder=2\n\n";
        byte[] data = a5er.getBytes(StandardCharsets.UTF_8);
        data = CsvTools.importNames(CsvTools.loadModel(data), out.names).data;
        data = CsvTools.importComments(CsvTools.loadModel(data), out.comments).data;
        data = CsvTools.importRelations(CsvTools.loadModel(data), out.relations).data;
        assertEquals(1, CsvTools.lastReport.done);
        String t = text(data);
        assertTrue(t.contains("PName=invoice__c\nLName=請求\n"));
        assertTrue(t.contains("Field=\"金額\",\"amount__c\",\"@INT\",,,\"\",\"税込\""));
        assertTrue(t.contains("Entity1=order__c\nEntity2=invoice__c\n"));
    }

    @Test
    void commandLine(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out");
        assertEquals(0, SfToCsv.Cli.run(new String[] {"folder", metadata(dir).toString(), "-o", out.toString()}));
        for (String f : List.of("sf_names.csv", "sf_relations.csv", "sf_comments.csv")) {
            assertTrue(Files.isRegularFile(out.resolve(f)), f);
        }
        assertEquals(1, SfToCsv.Cli.run(new String[] {"nope"}));
        assertEquals(1, SfToCsv.Cli.run(new String[] {"folder", dir.resolve("none").toString()}));
    }
}
