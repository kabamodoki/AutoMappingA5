import csv
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import a5er_csv as C  # noqa: E402
import a5er_layout as L  # noqa: E402
from test_a5er_layout import HEADER, entity, relation, sample  # noqa: E402


def csv_bytes(rows, enc="utf-8-sig"):
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\r\n").writerows(rows)
    return buf.getvalue().encode(enc)


def rows_of(data):
    return list(csv.reader(io.StringIO(data.decode("utf-8-sig"))))


class NamesTest(unittest.TestCase):
    def test_roundtrip_is_identical(self):
        for crlf in (False, True):
            src = sample(crlf=crlf, bom=crlf)
            exported, n = C.export_names(C.load_model(src))
            self.assertTrue(exported.startswith(b"\xef\xbb\xbf"))
            self.assertEqual(n, 9 * 3)
            out, rep = C.import_names(C.load_model(src), exported)
            self.assertEqual(out, src)
            self.assertEqual(rep.done, 0)
            self.assertEqual(rep.skipped, [])

    def test_export_format(self):
        rows = rows_of(C.export_names(C.load_model(sample()))[0])
        self.assertEqual(rows[0], C.NAMES_HEADER)
        self.assertEqual(rows[1], ["users", "", "usersの論理名"])
        self.assertEqual(rows[2], ["users", "col0", "列0"])

    def test_same_column_name_different_tables(self):
        src = sample()
        data = csv_bytes([C.NAMES_HEADER,
                          ["users", "col0", "ユーザーID"],
                          ["ORDERS", "COL0", "注文ID"],
                          ["users", "", "ユーザー"]])
        out, rep = C.import_names(C.load_model(src), data)
        self.assertEqual(rep.done, 3)
        m = C.load_model(out)
        users = m.find_entities("users")[0]
        orders = m.find_entities("orders")[0]
        self.assertEqual(users.lname, "ユーザー")
        self.assertEqual(users.find_column("col0").logical, "ユーザーID")
        self.assertEqual(orders.find_column("col0").logical, "注文ID")
        self.assertEqual(m.find_entities("products")[0].find_column("col0").logical, "列0")
        # Field の2つ目以降はそのまま
        self.assertIn('Field="ユーザーID","col0","@VARCHAR","NOT NULL",,"","",$FFFFFFFF,""\n', out.decode())

    def test_skip_and_empty_and_quote(self):
        data = csv_bytes([["users", "nothing", "x"], ["nope", "col0", "y"],
                          ["users", "col1", ""], ["users", "col1", 'A"B']])
        out, rep = C.import_names(C.load_model(sample()), data)
        self.assertEqual(rep.done, 1)
        self.assertEqual(len(rep.skipped), 2)
        self.assertIn("1 行目", rep.skipped[0])
        self.assertIn('Field="A""B","col1"', out.decode())
        self.assertEqual(C.load_model(out).find_entities("users")[0].find_column("col1").logical, 'A"B')

    def test_missing_lname_line_is_added(self):
        src = (HEADER + entity("t1")).replace("LName=t1の論理名\n", "").encode()
        out, rep = C.import_names(C.load_model(src), csv_bytes([["t1", "", "テーブル1"]]))
        self.assertEqual(rep.done, 1)
        self.assertIn("PName=t1\nLName=テーブル1\n", out.decode())

    def test_cp932_csv(self):
        data = csv_bytes([["users", "col0", "利用者番号"]], enc="cp932")
        out, rep = C.import_names(C.load_model(sample()), data)
        self.assertEqual(rep.done, 1)
        self.assertIn('Field="利用者番号"', out.decode())


class RelationsTest(unittest.TestCase):
    def test_roundtrip_adds_nothing(self):
        src = sample(crlf=True)
        exported, n = C.export_relations(C.load_model(src))
        self.assertEqual(n, 5)
        self.assertEqual(rows_of(exported)[1], ["users", "orders", "col0", "col1", "2", "3", "0"])
        out, rep = C.import_relations(C.load_model(src), exported)
        self.assertEqual(out, src)
        self.assertEqual(rep.unchanged, 5)

    def test_add_and_skip(self):
        src = sample()
        data = csv_bytes([C.RELATIONS_HEADER,
                          ["LOGS", "settings", "COL0", "col1", "", "", ""],
                          ["logs", "settings", "col0", "col1", "", "", ""],  # CSV 内の重複
                          ["logs", "nope", "col0", "col1", "", "", ""],
                          ["logs", "settings", "colX", "col1", "", "", ""],
                          ["logs", "settings", "col0,col1", "col1", "", "", ""],
                          ["users", "logs", "col0,col1", "col0,col1", "1", "4", "1"]])
        out, rep = C.import_relations(C.load_model(src), data)
        self.assertEqual(rep.done, 2)
        self.assertEqual(rep.unchanged, 1)
        self.assertEqual(len(rep.skipped), 3)
        text = out.decode()
        self.assertTrue(text.startswith(src.decode()))
        self.assertIn("[Relation]\nEntity1=logs\nEntity2=settings\nRelationType1=2\nRelationType2=3\n"
                      "Fields1=col0\nFields2=col1\n", text)
        self.assertIn("Fields1=col0,col1\nFields2=col0,col1\n", text)
        self.assertIn("Dependence=1\n", text)
        self.assertIn('Position="MAIN",0,5000,5000,5000,R,R,""\n', text)
        self.assertIn("ZOrder=6\n", text)
        self.assertIn("ZOrder=7\n", text)
        self.assertNotIn("\n\n\n", text)
        m = C.load_model(out)
        self.assertEqual(len(m.relations), 7)

    def test_added_relation_is_laid_out(self):
        data = csv_bytes([["logs", "settings", "col0", "col1"]])
        out, _ = C.import_relations(C.load_model(sample()), data)
        laid, _, _, unrouted = L.layout_bytes(out)
        self.assertEqual(unrouted, 0)
        self.assertEqual(L.parse(laid).relations[-1], ("logs", "settings"))
        from test_a5er_layout import check_layout
        check_layout(self, laid, expect_routed=5)  # 追加した線も含めて整列される

    def test_crlf_and_no_trailing_newline(self):
        src = (HEADER + entity("a") + entity("b")).rstrip("\n").replace("\n", "\r\n").encode()
        out, rep = C.import_relations(C.load_model(src), csv_bytes([["a", "b", "col0", "col0"]]))
        self.assertEqual(rep.done, 1)
        text = out.decode()
        self.assertNotIn("\n", text.replace("\r\n", ""))
        self.assertIn("ZOrder=1\r\n\r\n[Relation]\r\n", text)


class AllTest(unittest.TestCase):
    def test_csv_kind(self):
        self.assertEqual(C.csv_kind(csv_bytes([C.NAMES_HEADER, ["a", "b", "c"]])), "names")
        self.assertEqual(C.csv_kind(csv_bytes([C.RELATIONS_HEADER])), "relations")
        self.assertEqual(C.csv_kind(csv_bytes([["a", "", "x"]], enc="cp932")), "names")
        self.assertEqual(C.csv_kind(csv_bytes([["a", "b", "c", "d"]])), "relations")

    def test_run_all(self):
        names = csv_bytes([["logs", "col0", "ログID"], ["nope", "x", "y"]])
        rels = csv_bytes([C.RELATIONS_HEADER, ["logs", "settings", "col0", "col1", "", "", ""]])
        out, log = C.run_all(sample(), {"names": names, "relations": rels}, L.Options())
        text = out.decode()
        self.assertIn('Field="ログID","col0"', text)
        self.assertIn("Entity1=logs\nEntity2=settings\n", text)
        self.assertTrue(log[0].startswith("論理名を反映 1 件"))
        self.assertTrue(any(l.startswith("リレーションを追加 1 件") for l in log))
        self.assertTrue(log[-1].startswith("整列しました"))
        # 整列済み（追加したリレーションの線も決まっている）
        from test_a5er_layout import check_layout
        check_layout(self, out, expect_routed=5)

    def test_cli_all(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "er.a5er")
            n = os.path.join(d, "n.csv")
            r = os.path.join(d, "r.csv")
            for path, data in [(src, sample()), (n, csv_bytes([C.NAMES_HEADER])),
                               (r, csv_bytes([C.RELATIONS_HEADER]))]:
                with open(path, "wb") as f:
                    f.write(data)
            self.assertEqual(C.main(["all", src, r, n]), 0)
            self.assertTrue(os.path.isfile(os.path.join(d, "er_all.a5er")))
            self.assertEqual(C.main(["all", src]), 0)  # CSV 無しなら整列だけ
            self.assertEqual(C.main(["all", src, n, n]), 1)  # 同じ種類が2つ
            self.assertEqual(C.main(["all", src, n, "-o", n]), 1)


class CliTest(unittest.TestCase):
    def test_all_commands(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "er.a5er")
            with open(src, "wb") as f:
                f.write(sample())
            self.assertEqual(C.main(["export-names", src]), 0)
            self.assertEqual(C.main(["export-relations", src]), 0)
            self.assertEqual(C.main(["import-names", src, os.path.join(d, "er_names.csv")]), 0)
            self.assertEqual(C.main(["import-relations", src, os.path.join(d, "er_relations.csv")]), 0)
            for name in ("er_names.csv", "er_relations.csv", "er_names.a5er", "er_relations.a5er"):
                self.assertTrue(os.path.isfile(os.path.join(d, name)), name)
            self.assertEqual(C.main(["import-names", src, os.path.join(d, "er_names.csv"), "-o", src]), 1)


if __name__ == "__main__":
    unittest.main()
