import os
import random
import re
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import a5er_layout as L  # noqa: E402
import a5er_route as R  # noqa: E402

HEADER = (
    "# A5:ER FORMAT:16\n"
    "# A5:ER ENCODING:UTF8\n"
    "# A5:ER Mk-1 Copyright © 2007 m.matsubara\n"
    "\n"
    "[Manager]\n"
    "ProjectName=test\n"
    "Page=Main\n"
    'PageInfo="Main",2,"A3Landscape",$FFFFFF\n'
    'PageInfo="Sub",2,"A3Landscape",$FFFFFF\n'
    "\n"
)


def entity(name, page="MAIN", left=0, top=0, fields=2, size=True):
    pos = f'Position="{page}",{left},{top},200,{40 + fields * 16}' if size else f'Position="{page}",{left},{top}'
    flds = "".join(
        f'Field="列{i}","col{i}","@VARCHAR","NOT NULL",,"","",$FFFFFFFF,""\n' for i in range(fields)
    )
    return (
        "[Entity]\n"
        f"PName={name}\nLName={name}の論理名\nComment=\nTableOption=\n"
        f"Page={page}\nLeft={left}\nTop={top}\n{flds}"
        "EffectMode=None\nColor=$000000\nBkColor=$FFFFFF\n"
        f"{pos}\nZOrder=1\n\n"
    )


def relation(parent, child):
    return (
        "[Relation]\n"
        f"Entity1={parent}\nEntity2={child}\nRelationType1=2\nRelationType2=3\n"
        "Fields1=col0\nFields2=col1\nCardinarity1=\nCardinarity2=\n"
        'Position="MAIN",0,2048,7952,2089,R,R,""\n'
        "Dependence=0\nCaption=\nPName=\nLineMode=0\nBar1=205\nBar2=795\nBar3=209\n"
        "TermPos1=R\nTermPos2=R\nZOrder=5\n\n"
    )


def sample(crlf=False, bom=False, size=True):
    body = HEADER
    for n in ["users", "orders", "order_items", "products", "categories", "logs", "settings"]:
        body += entity(n, left=100, top=100, size=size)
    body += entity("sub_a", page="Sub", left=5, top=5, size=size)
    body += entity("sub_b", page="Sub", left=5, top=5, size=size)
    body += relation("users", "orders")
    body += relation("orders", "order_items")
    body += relation("products", "order_items")
    body += relation("categories", "products")
    body += relation("sub_a", "sub_b")
    if crlf:
        body = body.replace("\n", "\r\n")
    data = body.encode("utf-8")
    return (b"\xef\xbb\xbf" + data) if bom else data


def schema_a5er() -> bytes:
    """sample-db と同じ 20 テーブル（リレーション無し、Position に大きさ無し）。"""
    base = os.path.join(os.path.dirname(__file__), "..", "sample-db")
    with open(os.path.join(base, "init.sql"), encoding="utf-8") as f:
        sql = f.read()
    body = HEADER
    for t, cols in re.findall(r"CREATE TABLE (\w+) \((.*?)\n\);", sql, re.S):
        names = [ln.split()[0] for ln in cols.strip().split("\n") if not ln.strip().startswith("PRIMARY")]
        body += (f"[Entity]\nPName={t}\nLName={t}\nPage=Main\nLeft=0\nTop=0\n"
                 + "".join(f'Field="{c}","{c}","@INT","",,"","",$FFFFFFFF,""\n' for c in names)
                 + 'Position="Main",0,0\nZOrder=1\n\n')
    return body.encode("utf-8")


def schema_with_relations() -> bytes:
    import a5er_csv as C
    base = os.path.join(os.path.dirname(__file__), "..", "sample-db")
    with open(os.path.join(base, "sample_relations.csv"), "rb") as f:
        data, rep = C.import_relations(C.load_model(schema_a5er()), f.read())
    assert rep.done == 22
    return data


POS_LINE = re.compile(r"^(Left|Top|Position|Bar[123])=")


def boxes(data: bytes):
    """テーブル名 -> (ページ, x, y, w, h)"""
    doc = L.parse(data)
    out = {}
    for t in doc.tables:
        for p in t.placements:
            body, _ = L.split_eol(doc.lines[p.pos_idx])
            page, rest = L.parse_position(body.split("=", 1)[1])
            x, y = int(rest[0]), int(rest[1])
            out[t.name] = (p.page, x, y, p.w, p.h)
    return out


def check_layout(test, data: bytes, expect_routed: int | None = None, src: bytes | None = None) -> int:
    """出力ファイルを読み直し、A5:SQL Mk-2 の線の決まりで線を描いて確かめる。
    テーブル（帯を含む）が重ならない・線がほかのテーブルにかからない・線どうしが重ならない。
    src を渡すと、src から変わっていない線（線を引けず元のまま残したもの）は調べない。"""
    doc = L.parse(data)
    src_lines = L.parse(src).lines if src is not None else None
    k = doc.font_size / 6
    by_page: dict[str, dict[str, R.Box]] = {}
    loops = {r.parent for r in doc.relation_list if r.parent == r.child}
    for t in doc.tables:
        for p in t.placements:
            body, _ = L.split_eol(doc.lines[p.pos_idx])
            _, rest = L.parse_position(body.split("=", 1)[1])
            p.x, p.y = int(rest[0]), int(rest[1])
            by_page.setdefault(p.page, {})[t.name] = L.to_box(p, k, t.name in loops)
    for page, bx in by_page.items():
        items = list(bx.items())
        for i, (n1, b1) in enumerate(items):
            o1 = b1.occupied()
            for n2, b2 in items[i + 1:]:
                o2 = b2.occupied()
                test.assertFalse(o1[0] < o2[2] and o2[0] < o1[2] and o1[1] < o2[3] and o2[1] < o1[3],
                                 ("テーブルの重なり", page, n1, n2))
    lines = []
    for rel in doc.relation_list:
        if rel.parent == rel.child:
            continue
        for idx, page in rel.pos_lines:
            bx = by_page.get(page, {})
            if rel.parent not in bx or rel.child not in bx:
                continue
            if src_lines is not None and src_lines[idx] == doc.lines[idx]:
                continue
            body, _ = L.split_eol(doc.lines[idx])
            _, rest = L.parse_position(body.split("=", 1)[1])
            bars = tuple(int(v) / 10000 for v in rest[1:4])
            p, c = bx[rel.parent], bx[rel.child]
            mode = R.direction(p, c)
            test.assertIsNotNone(mode, ("向きがあいまい", rel.parent, rel.child))
            line = R.to_line(R.line_points(p, c, bars, mode))
            for name, b in bx.items():
                if name in (rel.parent, rel.child):
                    continue
                for s in line.segments:
                    test.assertFalse(R.seg_hits_rect(s, b.occupied(), 0), ("線がテーブルにかかる", rel.parent, rel.child, name))
            lines.append((page, rel, line))
    for i, (pg1, r1, l1) in enumerate(lines):
        for pg2, r2, l2 in lines[i + 1:]:
            if pg1 == pg2:
                test.assertIsNotNone(R.compare(l1, l2), ("線の重なり", r1.parent, r1.child, r2.parent, r2.child))
    if expect_routed is not None:
        test.assertEqual(len(lines), expect_routed)
    return len(lines)


class LayoutTest(unittest.TestCase):
    def run_layout(self, data, **kw):
        out, _, _, unrouted = L.layout_bytes(data, L.Options(**kw))
        return out

    def test_only_position_lines_change(self):
        for mode in ("compact", "layered"):
            for crlf in (False, True):
                for bom in (False, True):
                    src = sample(crlf=crlf, bom=bom)
                    out = self.run_layout(src, mode=mode)
                    self.assertEqual(src.startswith(b"\xef\xbb\xbf"), out.startswith(b"\xef\xbb\xbf"))
                    a = src.decode("utf-8-sig").split("\n")
                    b = out.decode("utf-8-sig").split("\n")
                    self.assertEqual(len(a), len(b))
                    changed = 0
                    for x, y in zip(a, b):
                        if x != y:
                            self.assertTrue(POS_LINE.match(x), x)
                            self.assertEqual(x.endswith("\r"), y.endswith("\r"))
                            changed += 1
                    self.assertGreater(changed, 0)

    def test_layered_parent_above_child(self):
        b = boxes(self.run_layout(sample(), mode="layered"))
        for parent, child in [("users", "orders"), ("orders", "order_items"),
                              ("products", "order_items"), ("categories", "products"),
                              ("sub_a", "sub_b")]:
            self.assertLess(b[parent][2], b[child][2], (parent, child))

    def test_no_overlap(self):
        for mode in ("compact", "layered", "grid"):
            out = self.run_layout(sample(), mode=mode, max_width=500)
            check_layout(self, out, src=sample())

    def test_pages_are_separate(self):
        b = boxes(self.run_layout(sample()))
        self.assertEqual(b["sub_a"][0], "SUB")
        self.assertEqual(min(b["sub_a"][1], b["sub_b"][1]), 50)  # Sub ページも左端が余白の位置
        self.assertEqual(b["users"][0], "MAIN")

    def test_left_top_follow_position(self):
        out = self.run_layout(sample()).decode("utf-8")
        for sec in out.split("[Entity]")[1:]:
            left = re.search(r"^Left=(\d+)", sec, re.M).group(1)
            top = re.search(r"^Top=(\d+)", sec, re.M).group(1)
            pos = re.search(r'^Position="[^"]*",(\d+),(\d+)', sec, re.M)
            self.assertEqual((left, top), pos.groups())

    def test_without_size_in_position(self):
        out = self.run_layout(sample(size=False))
        self.assertIn(b'Position="MAIN",', out)
        self.assertNotEqual(out, sample(size=False))
        check_layout(self, out, expect_routed=4)

    def test_layered_singletons_below_groups(self):
        b = boxes(self.run_layout(sample(), mode="layered"))
        grouped_bottom = max(b[n][2] + b[n][4] for n in ["users", "orders", "order_items", "products", "categories"])
        self.assertGreater(b["logs"][2], grouped_bottom)

    def test_cycle(self):
        data = (HEADER + entity("a") + entity("b") + entity("c")
                + relation("a", "b") + relation("b", "c") + relation("c", "a")).encode("utf-8")
        for mode in ("compact", "layered"):
            check_layout(self, self.run_layout(data, mode=mode), src=data)

    def test_stable(self):
        for mode in ("compact", "layered"):
            once = self.run_layout(sample(), mode=mode)
            self.assertEqual(once, self.run_layout(once, mode=mode))

    def test_no_entity(self):
        with self.assertRaises(ValueError):
            L.layout_bytes(HEADER.encode("utf-8"))

    def test_keep_lines(self):
        src = sample()
        out = self.run_layout(src, keep_lines=True)
        for x, y in zip(src.decode().split("\n"), out.decode().split("\n")):
            if x != y:
                self.assertTrue(re.match(r"^(Left|Top|Position=\"(MAIN|Sub)\",\d+,\d+,\d+,\d+$)", x), x)
        self.assertEqual(out.decode().count('Position="MAIN",0,2048,7952,2089,R,R,""'), 5)

    def test_lines_are_routed(self):
        out = self.run_layout(sample()).decode()
        # sub_a→sub_b は Position が別ページ（MAIN）なので変えない。それ以外の 4 本は決め直される
        self.assertEqual(out.count('Position="MAIN",0,2048,7952,2089,R,R,""'), 1)
        for sec in out.split("[Relation]")[1:]:
            bars = [int(re.search(rf"^Bar{i}=(\d+)", sec, re.M).group(1)) for i in (1, 2, 3)]
            pos = re.search(r'^Position="MAIN",0,(\d+),(\d+),(\d+),R,R,""', sec, re.M)
            self.assertEqual([round(int(v) / 10) for v in pos.groups()], bars)
            self.assertTrue(all(0 <= b <= 1000 for b in bars))

    def test_cli(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "er.a5er")
            with open(src, "wb") as f:
                f.write(sample())
            self.assertEqual(L.main([src]), 0)
            self.assertTrue(os.path.isfile(os.path.join(d, "er_layout.a5er")))
            self.assertEqual(L.main([src, "-o", src]), 1)


class RouteModelTest(unittest.TestCase):
    """A5:SQL Mk-2 の実測（localtest/calibration.a5er）と線の計算が合うこと。"""

    def box(self, x, y, rows):
        return R.Box(x, y, 140, rows * 26 + 8, label_h=60)

    def test_direction(self):
        self.assertEqual(R.direction(self.box(100, 100, 3), self.box(600, 700, 3)), "v")  # A
        self.assertEqual(R.direction(self.box(1200, 700, 3), self.box(1700, 100, 3)), "v")  # B
        self.assertEqual(R.direction(self.box(100, 1200, 12), self.box(700, 1300, 3)), "h")  # C
        self.assertEqual(R.direction(self.box(1800, 1200, 12), self.box(1200, 1300, 3)), "h")  # D
        self.assertEqual(R.direction(self.box(100, 1800, 3), self.box(1000, 1950, 3)), "h")  # E

    def test_bar2_counts_from_upper_or_left(self):
        bars = (0.2, 0.3, 0.8)
        a_p, a_c = self.box(100, 100, 3), self.box(600, 700, 3)
        b_p, b_c = self.box(1200, 700, 3), self.box(1700, 100, 3)
        ya = R.line_points(a_p, a_c, bars, "v")[1][1]
        yb = R.line_points(b_p, b_c, bars, "v")[1][1]
        self.assertAlmostEqual(ya - 100, yb - 100)  # 親が上でも下でも、上のテーブルから同じ割合
        d_p, d_c = self.box(1800, 1200, 12), self.box(1200, 1300, 3)
        x = R.line_points(d_p, d_c, bars, "h")[1][0]
        self.assertAlmostEqual(x, d_c.right + 0.3 * (d_p.x - d_c.right))  # 左のテーブル（子）から数える


class CompactTest(unittest.TestCase):
    def test_sample_db_schema(self):
        out, _, _, unrouted = L.layout_bytes(schema_with_relations())
        self.assertEqual(unrouted, 0)
        check_layout(self, out, expect_routed=21)  # 自己参照の 1 本を除く
        b = boxes(out)
        w = max(v[1] + v[3] for v in b.values()) - min(v[1] for v in b.values())
        h = max(v[2] + v[4] for v in b.values()) - min(v[2] for v in b.values())
        self.assertLess(max(w, h) / min(w, h), 2.0)

    def test_layered_sample_db_schema(self):
        src = schema_with_relations()
        out, _, _, _ = L.layout_bytes(src, L.Options(mode="layered"))
        check_layout(self, out, src=src)

    def test_random_graphs(self):
        rnd = random.Random(7)
        for trial in range(3):
            n = 15
            body = HEADER + "".join(entity(f"t{i:02d}", fields=rnd.choice([2, 3, 5, 12, 30]), size=False)
                                    for i in range(n))
            pairs = set()
            while len(pairs) < 18:
                a, b = rnd.sample(range(n), 2)
                pairs.add((a, b))
            for a, b in sorted(pairs):
                body += relation(f"t{a:02d}", f"t{b:02d}")
            out, _, _, unrouted = L.layout_bytes(body.encode())
            print("random", trial, "unrouted", unrouted)
            self.assertLessEqual(unrouted, 1)
            check_layout(self, out, src=body.encode())

    def test_speed(self):
        start = time.time()
        L.layout_bytes(schema_with_relations())
        self.assertLess(time.time() - start, 30)


if __name__ == "__main__":
    unittest.main()
