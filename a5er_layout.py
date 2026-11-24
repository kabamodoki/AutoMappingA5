"""A5:SQL Mk-2 の ER 図ファイル（.a5er）のテーブル配置を自動で整列する。

使い方:
    python a5er_layout.py 入力.a5er [-o 出力.a5er] [--mode layered|grid]
                          [--gap-x 60] [--gap-y 100] [--max-width 3000] [--margin 50]
                          [--keep-lines]   （--mode の既定は compact）

書き換えるのはテーブルの Left= / Top= / Position= の行と、リレーション線の
Position= / Bar1〜3= の行だけ。それ以外の行は文字コード・改行コードを含めて元のまま残す。
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import sys
import unicodedata
from dataclasses import dataclass, field

import a5er_route as R

TABLE_SECTIONS = {"Entity", "View"}
DEFAULT_PAGE = "MAIN"
UTF8_BOM = b"\xef\xbb\xbf"

# 大きさの推定（A5:SQL Mk-2 の画面を測って決めた値。FontSize=6 のとき）
BASE_FONT_SIZE = 6
WIDTH_KANJI = 22  # 漢字など全角 1 文字
WIDTH_KANA = 16  # カタカナ・ひらがな 1 文字
WIDTH_ASCII = 10  # 半角 1 文字
WIDTH_FK = 40  # 外部キーのカラムに付く「 (FK)」
BOX_PADDING_X = 34
MIN_WIDTH = 130
ROW_HEIGHT = 25.3
BOX_PADDING_Y = 2
TEXT_HEIGHT = 26  # テーブル名の文字の高さ（縦向きの線の Bar2 の基準）
LABEL_HEIGHT = 60  # テーブル名の帯（枠の上。上から入る線はこの上で止まる）
MAX_TRIED_POSITIONS = 60  # compact: 1つのテーブルについて線まで調べる置き場所の数の上限

# Position="ページ",左,上[,幅,高さ...] のページ名部分と残りに分ける
_POSITION_RE = re.compile(r'^("(?:[^"]|"")*"|[^,]*),(.*)$')


# ---------------------------------------------------------------------------
# データ
# ---------------------------------------------------------------------------


@dataclass
class Placement:
    """1つのテーブルの、1つのページ上の配置。"""

    table: "Table"
    page: str  # 大文字にそろえたページ名
    pos_idx: int | None  # Position 行の行番号（無ければ None）
    w: int = 0
    h: int = 0
    label_w: int = 0
    x: int = 0
    y: int = 0


@dataclass
class Table:
    pname: str = ""
    lname: str = ""
    page: str = ""
    fields: list[tuple[str, str]] = field(default_factory=list)
    left_idx: int | None = None
    top_idx: int | None = None
    pos_lines: list[int] = field(default_factory=list)
    placements: list[Placement] = field(default_factory=list)

    @property
    def name(self) -> str:
        return self.pname or self.lname


@dataclass
class Relation:
    parent: str = ""
    child: str = ""
    fields2: str = ""
    pos_lines: list[tuple[int, str]] = field(default_factory=list)  # (行番号, 大文字のページ名)
    bar_idx: dict[int, int] = field(default_factory=dict)  # 1〜3 → 行番号
    routes: dict[str, tuple[int, int, int]] = field(default_factory=dict)  # ページ → Bar×10


@dataclass
class Document:
    lines: list[str]  # 改行コードを含む行
    encoding: str
    bom: bool
    tables: list[Table]
    relation_list: list[Relation]
    font_size: float = BASE_FONT_SIZE
    main_page: str = DEFAULT_PAGE
    logical_view: bool = True
    unrouted: int = 0  # 線を引けなかった Relation の数

    @property
    def relations(self) -> list[tuple[str, str]]:
        return [(r.parent, r.child) for r in self.relation_list]


# ---------------------------------------------------------------------------
# 読み込み
# ---------------------------------------------------------------------------


def split_eol(line: str) -> tuple[str, str]:
    body = line.rstrip("\r\n")
    return body, line[len(body):]


def decode(data: bytes) -> tuple[str, str, bool]:
    if data.startswith(UTF8_BOM):
        return data[len(UTF8_BOM):].decode("utf-8"), "utf-8", True
    try:
        return data.decode("utf-8"), "utf-8", False
    except UnicodeDecodeError:
        return data.decode("cp932"), "cp932", False


def split_lines(text: str) -> list[str]:
    parts = text.split("\n")
    lines = [p + "\n" for p in parts[:-1]]
    if parts[-1]:
        lines.append(parts[-1])
    return lines


def parse_position(value: str) -> tuple[str, list[str]] | None:
    m = _POSITION_RE.match(value)
    if not m:
        return None
    return m.group(1), m.group(2).split(",")


def unquote(token: str) -> str:
    token = token.strip()
    if len(token) >= 2 and token[0] == '"' and token[-1] == '"':
        return token[1:-1].replace('""', '"')
    return token


def to_int(s: str) -> int | None:
    try:
        return int(s.strip())
    except ValueError:
        return None


def parse(data: bytes) -> Document:
    text, encoding, bom = decode(data)
    lines = split_lines(text)
    tables: list[Table] = []
    relations: list[Relation] = []
    font_size: float = BASE_FONT_SIZE
    main_page = DEFAULT_PAGE
    logical_view = True

    section = None
    table: Table | None = None
    rel: Relation | None = None

    for i, line in enumerate(lines):
        body, _ = split_eol(line)
        stripped = body.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            section = stripped[1:-1]
            table = rel = None
            if section in TABLE_SECTIONS:
                table = Table()
                tables.append(table)
            elif section == "Relation":
                rel = Relation()
                relations.append(rel)
            continue
        if "=" not in body or body.startswith("#"):
            continue
        key, value = body.split("=", 1)
        key = key.strip()
        if table is not None:
            if key == "PName":
                table.pname = value
            elif key == "LName":
                table.lname = value
            elif key == "Page":
                table.page = value
            elif key == "Left":
                table.left_idx = i
            elif key == "Top":
                table.top_idx = i
            elif key == "Position":
                table.pos_lines.append(i)
            elif key == "Field":
                cols = next(csv.reader([value]), [])
                table.fields.append(
                    (cols[0] if cols else "", cols[1] if len(cols) > 1 else "")
                )
        elif rel is not None:
            if key == "Entity1":
                rel.parent = value
            elif key == "Entity2":
                rel.child = value
            elif key == "Fields2":
                rel.fields2 = value
            elif key == "Position":
                parsed = parse_position(value)
                if parsed:
                    rel.pos_lines.append((i, (unquote(parsed[0]) or DEFAULT_PAGE).upper()))
            elif key in ("Bar1", "Bar2", "Bar3"):
                rel.bar_idx[int(key[-1])] = i
        elif section == "Manager":
            if key == "FontSize":
                try:
                    font_size = float(value) or BASE_FONT_SIZE
                except ValueError:
                    pass
            elif key == "Page" and value:
                main_page = value
            elif key == "LogicalView":
                logical_view = value.strip() != "0"

    tables = [t for t in tables if t.name]
    relations = [r for r in relations if r.parent and r.child]
    fk_cols: dict[str, set[str]] = {}
    for r in relations:
        cols = {c.strip().lower() for c in r.fields2.split(",") if c.strip()}
        fk_cols.setdefault(r.child, set()).update(cols)
    k = font_size / BASE_FONT_SIZE
    for t in tables:
        build_placements(t, lines, fk_cols.get(t.name, set()), k, logical_view)
    return Document(lines, encoding, bom, tables, relations, font_size, main_page, logical_view)


def build_placements(t: Table, lines: list[str], fk: set[str], k: float, logical_view: bool = True) -> None:
    est_w, est_h = estimate_size(t, fk, k, logical_view)
    label = (t.lname or t.pname) if logical_view else (t.pname or t.lname)
    label_w = round(text_width(label) * k)
    for idx in t.pos_lines:
        body, _ = split_eol(lines[idx])
        parsed = parse_position(body.split("=", 1)[1])
        if parsed is None:
            continue
        page_token, rest = parsed
        w = to_int(rest[2]) if len(rest) > 2 else None
        h = to_int(rest[3]) if len(rest) > 3 else None
        t.placements.append(
            Placement(
                table=t,
                page=(unquote(page_token) or DEFAULT_PAGE).upper(),
                pos_idx=idx,
                w=w if w and w > 0 else est_w,
                h=h if h and h > 0 else est_h,
                label_w=label_w,
            )
        )
    if not t.placements:
        t.placements.append(
            Placement(table=t, page=(t.page or DEFAULT_PAGE).upper(), pos_idx=None,
                      w=est_w, h=est_h, label_w=label_w)
        )


def text_width(s: str) -> int:
    """FontSize=6 のときの文字列の表示幅（実測の近似）。"""
    w = 0
    for c in s:
        if "\u3040" <= c <= "\u30ff":
            w += WIDTH_KANA
        elif unicodedata.east_asian_width(c) in "WFA":
            w += WIDTH_KANJI
        else:
            w += WIDTH_ASCII
    return w


def estimate_size(t: Table, fk: set[str] | None = None, k: float = 1.0,
                  logical_view: bool = True) -> tuple[int, int]:
    """表示される名前（論理名表示なら論理名、物理名表示なら物理名）から大きさを見積もる。"""
    fk = fk or set()
    longest = 0
    for logical, physical in t.fields:
        shown = (logical or physical) if logical_view else (physical or logical)
        width = text_width(shown) + (WIDTH_FK if physical.strip().lower() in fk else 0)
        longest = max(longest, width)
    w = round(max(MIN_WIDTH, longest + BOX_PADDING_X) * k)
    h = round(len(t.fields) * ROW_HEIGHT * k) + BOX_PADDING_Y
    return w, h


# ---------------------------------------------------------------------------
# 整列
# ---------------------------------------------------------------------------


@dataclass
class Options:
    mode: str = "compact"
    gap_x: int = 60
    gap_y: int = 100
    max_width: int = 3000
    margin: int = 50
    keep_lines: bool = False


def sort_key(p: Placement) -> tuple[str, str]:
    return (p.table.name.lower(), p.table.name)


def place_grid(items: list[Placement], x0: int, y0: int, opt: Options) -> tuple[int, int]:
    """items を左から並べ、max_width で折り返す。(右端, 下端) を返す。"""
    x, y, row_h, right = x0, y0, 0, x0
    for p in items:
        if x > x0 and x + p.w > x0 + opt.max_width:
            x, y, row_h = x0, y + row_h + opt.gap_y, 0
        p.x, p.y = x, y
        x += p.w + opt.gap_x
        row_h = max(row_h, p.h)
        right = max(right, p.x + p.w)
    return right, (y + row_h if items else y0)


def components(n: int, adj: dict[int, set[int]]) -> list[list[int]]:
    seen: set[int] = set()
    result = []
    for i in range(n):
        if i in seen:
            continue
        stack, comp = [i], []
        seen.add(i)
        while stack:
            v = stack.pop()
            comp.append(v)
            for u in adj[v]:
                if u not in seen:
                    seen.add(u)
                    stack.append(u)
        result.append(comp)
    return result


# --- layered ---------------------------------------------------------------


def remove_cycles(comp: list[int], children: dict[int, list[int]]) -> dict[int, list[int]]:
    """名前順の DFS で戻り辺を除いた辺（親 → 子の一覧）を返す。"""
    members = set(comp)
    state: dict[int, int] = {}  # 1=探索中 2=完了
    dag: dict[int, list[int]] = {v: [] for v in comp}
    for start in sorted(comp):
        if start in state:
            continue
        state[start] = 1
        stack = [(start, iter(children[start]))]
        while stack:
            v, it = stack[-1]
            nxt = next(it, None)
            if nxt is None:
                state[v] = 2
                stack.pop()
                continue
            if nxt not in members:
                continue
            st = state.get(nxt)
            if st == 1:
                continue  # 戻り辺（循環）は無視
            dag[v].append(nxt)
            if st is None:
                state[nxt] = 1
                stack.append((nxt, iter(children[nxt])))
    return dag


def assign_layers(comp: list[int], dag: dict[int, list[int]]) -> dict[int, int]:
    """最長経路で段を決め、親の無いテーブルを子の直上まで下げる。"""
    parents: dict[int, list[int]] = {v: [] for v in comp}
    for v in comp:
        for u in dag[v]:
            parents[u].append(v)
    indeg = {v: len(parents[v]) for v in comp}
    layer = {v: 0 for v in comp}
    queue = [v for v in sorted(comp) if indeg[v] == 0]
    while queue:
        v = queue.pop(0)
        for u in dag[v]:
            layer[u] = max(layer[u], layer[v] + 1)
            indeg[u] -= 1
            if indeg[u] == 0:
                queue.append(u)
    for v in comp:
        if not parents[v] and dag[v]:
            layer[v] = min(layer[c] for c in dag[v]) - 1
    low = min(layer.values())
    return {v: layer[v] - low for v in comp}


def order_layers(layers: list[list[int]], up: dict[int, list[int]], down: dict[int, list[int]]) -> None:
    """隣の段でつながる相手の並び位置の平均（重心）で段内を並べ替える。"""

    def positions() -> dict[int, float]:
        pos = {}
        for row in layers:
            n = len(row)
            for i, v in enumerate(row):
                pos[v] = (i + 0.5) / n
        return pos

    def sweep(indices, nbrs: dict[int, list[int]]) -> None:
        for li in indices:
            pos = positions()

            def key(v: int) -> float:
                ns = [pos[u] for u in nbrs[v]]
                return sum(ns) / len(ns) if ns else pos[v]

            layers[li].sort(key=key)

    n = len(layers)
    for _ in range(8):
        sweep(range(1, n), up)
        sweep(range(n - 2, -1, -1), down)


def build_rows(nodes: list[Placement], comp: list[int], dag: dict[int, list[int]],
               layer_of: dict[int, int], opt: Options) -> list[list[int]]:
    """段を決めて並べ替え、max_width で折り返した行（テーブル番号の並び）を返す。"""
    depth = max(layer_of.values()) + 1
    layers: list[list[int]] = [[] for _ in range(depth)]
    for v in sorted(comp):
        layers[layer_of[v]].append(v)

    # 2段以上離れた辺に仮の点を置く
    up: dict[int, list[int]] = {v: [] for v in comp}
    down: dict[int, list[int]] = {v: [] for v in comp}
    next_id = len(nodes)
    for v in sorted(comp):
        for c in dag[v]:
            prev = v
            for li in range(layer_of[v] + 1, layer_of[c]):
                d = next_id
                next_id += 1
                layers[li].append(d)
                up[d], down[d] = [prev], []
                down[prev].append(d)
                prev = d
            down[prev].append(c)
            up[c].append(prev)
    order_layers(layers, up, down)

    rows: list[list[int]] = []
    for layer in layers:
        cur: list[int] = []
        width = 0
        for v in layer:
            if v >= len(nodes):
                continue  # 仮の点
            w = nodes[v].w
            add = w if not cur else opt.gap_x + w
            if cur and width + add > opt.max_width:
                rows.append(cur)
                cur, width, add = [], 0, w
            cur.append(v)
            width += add
        if cur:
            rows.append(cur)
    return rows


def place_x(nodes: list[Placement], rows: list[list[int]], parents: dict[int, list[int]],
            row_of: dict[int, int], opt: Options) -> None:
    """上の行から順に、親の中心の平均に寄せて横位置を決める。"""
    for r, row in enumerate(rows):
        right = None
        for v in row:
            p = nodes[v]
            ups = [nodes[u] for u in parents[v] if row_of[u] < r]
            want = round(sum(u.x + u.w / 2 for u in ups) / len(ups) - p.w / 2) if ups else None
            if right is None:
                x = want if want is not None else 0
            else:
                x = max(want, right + opt.gap_x) if want is not None else right + opt.gap_x
            p.x = x
            right = x + p.w
    low = min(nodes[v].x for row in rows for v in row)
    for row in rows:
        for v in row:
            nodes[v].x -= low


def layout_component(nodes: list[Placement], comp: list[int], children: dict[int, list[int]],
                     opt: Options, k: float) -> tuple[int, int]:
    """島を (0,0) 基準で段組みに並べ、(幅, 高さ) を返す。"""
    dag = remove_cycles(comp, children)
    layer_of = assign_layers(comp, dag)
    rows = build_rows(nodes, comp, dag, layer_of, opt)
    row_of = {v: r for r, row in enumerate(rows) for v in row}
    parents: dict[int, list[int]] = {v: [] for v in comp}
    for v in comp:
        for c in dag[v]:
            parents[c].append(v)
    place_x(nodes, rows, parents, row_of, opt)

    gap = max(opt.gap_y, round(LABEL_HEIGHT * k) + 80)
    y = 0
    for row in rows:
        for v in row:
            nodes[v].y = y
        y += max(nodes[v].h for v in row) + gap
    height = y - gap
    width = max(nodes[v].x + nodes[v].w for v in comp)
    return width, height


def layered_page(nodes: list[Placement], rels: list[tuple[Relation, int, int]], opt: Options, k: float) -> None:
    children: dict[int, list[int]] = {i: [] for i in range(len(nodes))}
    adj: dict[int, set[int]] = {i: set() for i in range(len(nodes))}
    for _, a, b in rels:
        if b not in children[a]:
            children[a].append(b)
        adj[a].add(b)
        adj[b].add(a)
    for a in children:
        children[a].sort()

    comps = components(len(nodes), adj)
    groups = [sorted(c) for c in comps if len(c) > 1]
    singles = sorted((c[0] for c in comps if len(c) == 1))
    groups.sort(key=lambda c: (-len(c), c[0]))

    # 島を棚詰め（ラベルの分だけ下げて始める）
    x0 = opt.margin
    y0 = opt.margin + round(LABEL_HEIGHT * k)
    x, y, shelf_h, bottom = x0, y0, 0, y0
    comp_gap = opt.gap_x * 2
    for comp in groups:
        w, h = layout_component(nodes, comp, children, opt, k)
        if x > x0 and x + w > x0 + opt.max_width:
            x, y, shelf_h = x0, y + shelf_h + opt.gap_y, 0
        for v in comp:
            nodes[v].x += x
            nodes[v].y += y
        x += w + comp_gap
        shelf_h = max(shelf_h, h)
        bottom = y + shelf_h

    start_y = bottom + opt.gap_y + round(LABEL_HEIGHT * k) if groups else y0
    place_grid([nodes[i] for i in singles], x0, start_y, opt)


# --- 線 ---------------------------------------------------------------------


def to_box(p: Placement, k: float, self_loop: bool, x: float | None = None, y: float | None = None) -> R.Box:
    return R.Box(p.x if x is None else x, p.y if y is None else y, p.w, p.h,
                 label_h=round(LABEL_HEIGHT * k), text_h=round(TEXT_HEIGHT * k),
                 label_w=p.label_w, self_loop=self_loop)


def route_fixed(nodes: list[Placement], rels: list[tuple[Relation, int, int]], page: str,
                k: float, loops: set[int]) -> int:
    """テーブルを動かさずに線を探す。線を引けなかった数を返す。"""
    boxes = [to_box(p, k, i in loops) for i, p in enumerate(nodes)]
    occupied = [b.occupied() for b in boxes]
    lines: list[R.Line] = []
    failed = 0

    def dist(e):
        _, a, b = e
        return max(R.gaps(boxes[a], boxes[b]))

    for rel, a, b in sorted(rels, key=dist):
        obstacles = [o for i, o in enumerate(occupied) if i not in (a, b)]
        r = R.route(boxes[a], boxes[b], obstacles, lines) or R.route(boxes[a], boxes[b], obstacles, lines, True)
        if r is None:
            failed += 1
            continue
        lines.append(r.line)
        rel.routes[page] = R.bars_to_int(r.bars)
    return failed


# --- compact ---------------------------------------------------------------


COMPACT_VARIANTS = 4  # 置く順番を変えて試す数


def compact_page(nodes: list[Placement], rels: list[tuple[Relation, int, int]], page: str,
                 opt: Options, k: float, loops: set[int]) -> int:
    """線が重ならないように、全体を正方形に近くまとめる。線を引けなかった数を返す。
    置く順番を変えて何通りか試し、線を引けなかった数 → 全体の大きさ の順に一番良いものを採る。"""
    best = None
    for variant in range(COMPACT_VARIANTS):
        result = compact_once(nodes, rels, opt, k, loops, variant)
        failed, positions, bars_of, (w, h) = result
        key = (len(failed), max(w, h), w + h)
        if best is None or key < best[0]:
            best = (key, result)
    failed, positions, bars_of, _ = best[1]
    for i, (x, y) in positions.items():
        nodes[i].x, nodes[i].y = x, y
    for e, bars in bars_of.items():
        rels[e][0].routes[page] = bars
    return len(failed)


def compact_once(nodes: list[Placement], rels: list[tuple[Relation, int, int]],
                 opt: Options, k: float, loops: set[int], variant: int):
    """1 通りの順番でコンパクト配置を作る。(線を引けなかった番号, 位置, Bar, (幅, 高さ)) を返す。"""
    n = len(nodes)
    nbrs: dict[int, set[int]] = {i: set() for i in range(n)}
    for _, a, b in rels:
        nbrs[a].add(b)
        nbrs[b].add(a)
    boxes: dict[int, R.Box] = {}
    name = lambda i: nodes[i].table.name  # noqa: E731
    area = lambda i: nodes[i].w * nodes[i].h  # noqa: E731

    def bbox() -> tuple[float, float, float, float]:
        occ = [b.occupied() for b in boxes.values()]
        return (min(o[0] for o in occ), min(o[1] for o in occ), max(o[2] for o in occ), max(o[3] for o in occ))

    def candidates(i: int, bases: list[int], mults: tuple[int, ...]) -> list[tuple[int, int]]:
        p = nodes[i]
        probe = to_box(p, k, i in loops, 0, 0).occupied()  # (x0, y0, x1, y1) の (0,0) からのずれ
        occ_w, top_off = probe[2], -probe[1]
        out: set[tuple[int, int]] = set()
        for j in bases:
            nb = boxes[j]
            o = nb.occupied()
            for m in mults:
                dx, dy = opt.gap_x * m, opt.gap_y * m
                ys = (nb.y, nb.y + (nb.h - p.h) / 2, nb.bottom - p.h)
                xs = (nb.x, nb.x + (nb.w - p.w) / 2, nb.right - p.w)
                for yy in ys:
                    out.add((round(o[2] + dx), round(yy)))
                    out.add((round(o[0] - dx - occ_w), round(yy)))
                for xx in xs:
                    out.add((round(xx), round(o[3] + dy + top_off)))
                    out.add((round(xx), round(o[1] - dy - p.h)))
        # つながる相手が 2 つ以上: 「A の横位置 × B の縦位置」の組み合わせ
        if 2 <= len(bases) <= 6:
            xs_all, ys_all = set(), set()
            for j in bases:
                nb = boxes[j]
                o = nb.occupied()
                xs_all |= {nb.x, nb.x + (nb.w - p.w) / 2, o[2] + opt.gap_x, o[0] - opt.gap_x - occ_w}
                ys_all |= {nb.y, nb.y + (nb.h - p.h) / 2, o[3] + opt.gap_y + top_off, o[1] - opt.gap_y - p.h}
            for xx in xs_all:
                for yy in ys_all:
                    out.add((round(xx), round(yy)))
        b = bbox()
        out.add((round(b[2] + opt.gap_x), round(b[1] + top_off)))
        out.add((round(b[0]), round(b[3] + opt.gap_y + top_off)))
        for q in boxes.values():
            out.add((round(b[2] + opt.gap_x), round(q.y)))
            out.add((round(q.x), round(b[3] + opt.gap_y + top_off)))
        return sorted(out)

    def area_term(b: tuple[float, float, float, float]) -> float:
        w, h = b[2] - b[0], b[3] - b[1]
        return 2 * max(w, h) + 0.5 * (w + h)

    line_of: dict[int, R.Line] = {}  # Relation の番号 → 確定した線
    bars_of: dict[int, tuple[int, int, int]] = {}  # Relation の番号 → Bar
    failed: set[int] = set()  # 線を引けなかった Relation の番号

    def try_place(i: int, mults: tuple[int, ...], strict: bool, fine: bool = False,
                  limit: int = MAX_TRIED_POSITIONS):
        bases = [j for j in nbrs[i] if j in boxes] or list(boxes)
        partners = [(e, a, b) for e, (_, a, b) in enumerate(rels)
                    if (a == i and b in boxes) or (b == i and a in boxes)]
        lines = list(line_of.values())
        cur = bbox()
        mx, my = opt.gap_x / 2, opt.gap_y / 2
        scored = []
        for x, y in candidates(i, bases, mults):
            t = to_box(nodes[i], k, i in loops, x, y)
            o = t.occupied()
            nb = (min(cur[0], o[0]), min(cur[1], o[1]), max(cur[2], o[2]), max(cur[3], o[3]))
            at = area_term(nb)
            lb = at
            for _, a, b in partners:
                other = boxes[b if a == i else a]
                lb += max(0.0, *R.gaps(t, other))
            scored.append((lb, at, x, y, t, o))
        scored.sort(key=lambda s: (s[0], s[2], s[3]))

        best = None
        occupied = [b.occupied() for b in boxes.values()]
        tried = 0
        for lb, at, x, y, t, o in scored:
            if best is not None and lb >= best[0]:
                break
            if tried >= limit:
                break
            if any(o[0] < q[2] + mx and o[2] > q[0] - mx and o[1] < q[3] + my and o[3] > q[1] - my
                   for q in occupied):
                continue
            if strict and R.rect_hits_lines(o, lines):
                continue
            tried += 1
            temp = list(lines)
            total = 0.0
            routes = []
            ok = True
            for e, a, b in partners:
                pa = t if a == i else boxes[a]
                pb = t if b == i else boxes[b]
                obstacles = [q.occupied() for j, q in boxes.items() if j not in (a, b)]
                r = R.route(pa, pb, obstacles, temp, fine)
                if r is None:
                    if strict:
                        ok = False
                        break
                    routes.append((e, None))
                    continue
                temp.append(r.line)
                total += r.cost
                routes.append((e, r))
            if not ok:
                continue
            score = at + total
            if best is None or score < best[0]:
                best = (score, t, routes)
        return best

    def commit(i: int, best) -> None:
        _, t, routes = best
        boxes[i] = t
        for e, r in routes:
            if r is None:
                failed.add(e)
                line_of.pop(e, None)
                bars_of.pop(e, None)
            else:
                failed.discard(e)
                line_of[e] = r.line
                bars_of[e] = R.bars_to_int(r.bars)

    def frontier_key(i: int):
        placed, deg = len(nbrs[i] & boxes.keys()), len(nbrs[i])
        return [(-placed, -deg, -area(i), name(i)), (-placed, -area(i), -deg, name(i)),
                (-deg, -placed, -area(i), name(i)), (-placed, -deg, area(i), name(i))][variant]

    def seed_key(i: int):
        deg = len(nbrs[i])
        return [(-deg, -area(i), name(i)), (-area(i), -deg, name(i)),
                (-deg, -area(i), name(i)), (-deg, area(i), name(i))][variant]

    while len(boxes) < n:
        frontier = [i for i in range(n) if i not in boxes and nbrs[i] & boxes.keys()]
        if frontier:
            i = min(frontier, key=frontier_key)
        else:
            i = min((i for i in range(n) if i not in boxes), key=seed_key)
        if not boxes:
            boxes[i] = to_box(nodes[i], k, i in loops, 0, 0)
            continue
        best = (try_place(i, (1, 2), True) or try_place(i, (1, 2), True, True)
                or try_place(i, (3, 4), True, True) or try_place(i, (1, 2, 3), False, True))
        commit(i, best)

    # 手直し: 線を引けなかった Relation の両端のどちらかを、全部の線が引ける場所へ置き直す
    for _ in range(5):
        if not failed:
            break
        for e in sorted(failed):
            if e not in failed:
                continue
            _, a, b = rels[e]
            for v in sorted((a, b), key=lambda v: (len(nbrs[v]), area(v), name(v))):
                if len(boxes) == 1:
                    break
                saved_box = boxes.pop(v)
                mine = [x for x, (_, pa, pb) in enumerate(rels) if v in (pa, pb)]
                saved_lines = {x: line_of.pop(x) for x in mine if x in line_of}
                best = (try_place(v, (1, 2), True, True, 10 ** 6) or try_place(v, (3, 4), True, True, 10 ** 6)
                        or try_place(v, (5, 6), True, True, 10 ** 6))
                if best is not None:
                    commit(v, best)
                    break
                boxes[v] = saved_box
                line_of.update(saved_lines)

    b = bbox()
    dx, dy = opt.margin - b[0], opt.margin - b[1]
    positions = {i: (round(t.x + dx), round(t.y + dy)) for i, t in boxes.items()}
    return failed, positions, bars_of, (b[2] - b[0], b[3] - b[1])


# --- ページ ------------------------------------------------------------------


def layout_page(nodes: list[Placement], relations: list[Relation], page: str, opt: Options, k: float) -> int:
    """1ページを整列し、線を引けなかった Relation の数を返す。"""
    nodes.sort(key=sort_key)
    if opt.mode == "grid":
        place_grid(nodes, opt.margin, opt.margin + round(LABEL_HEIGHT * k), opt)
        return 0

    index: dict[str, int] = {}
    for i, p in enumerate(nodes):
        index.setdefault(p.table.name, i)
    rels: list[tuple[Relation, int, int]] = []
    loops: set[int] = set()
    for rel in relations:
        a, b = index.get(rel.parent), index.get(rel.child)
        if a is None or b is None:
            continue
        if a == b:
            loops.add(a)
            continue
        rels.append((rel, a, b))

    if opt.mode == "compact":
        failed = compact_page(nodes, rels, page, opt, k, loops)
    else:
        for i in loops:  # 自己参照の線の分、右を空けて並べる
            nodes[i].w += R.SELF_LOOP_RIGHT
        layered_page(nodes, rels, opt, k)
        for i in loops:
            nodes[i].w -= R.SELF_LOOP_RIGHT
        failed = route_fixed(nodes, rels, page, k, loops)
    if opt.keep_lines:
        for rel, _, _ in rels:
            rel.routes.pop(page, None)
        return 0
    return failed


def arrange(doc: Document, opt: Options) -> int:
    """全ページを整列し、ページ数を返す。"""
    pages: dict[str, list[Placement]] = {}
    for t in doc.tables:
        for p in t.placements:
            pages.setdefault(p.page, []).append(p)
    k = doc.font_size / BASE_FONT_SIZE
    doc.unrouted = 0
    for page, nodes in pages.items():
        doc.unrouted += layout_page(nodes, doc.relation_list, page, opt, k)
    return len(pages)


# ---------------------------------------------------------------------------
# 書き出し
# ---------------------------------------------------------------------------


def replace_value(line: str, value: str) -> str:
    body, eol = split_eol(line)
    key = body.split("=", 1)[0]
    return f"{key}={value}{eol}"


def apply_relation(lines: list[str], rel: Relation, main_page: str) -> None:
    for page, bars in rel.routes.items():
        found = False
        for n, (idx, pos_page) in enumerate(rel.pos_lines):
            if pos_page != page:
                continue
            body, _ = split_eol(lines[idx])
            page_token, rest = parse_position(body.split("=", 1)[1])
            if len(rest) < 4:
                continue
            rest = rest[:1] + [str(b) for b in bars] + rest[4:]
            lines[idx] = replace_value(lines[idx], ",".join([page_token] + rest))
            found = n == 0
            break
        if found or (not rel.pos_lines and page == main_page.upper()):
            for i, b in enumerate(bars, start=1):
                if i in rel.bar_idx:
                    lines[rel.bar_idx[i]] = replace_value(lines[rel.bar_idx[i]], str(round(b / 10)))


def apply(doc: Document) -> list[str]:
    lines = list(doc.lines)
    for t in doc.tables:
        for p in t.placements:
            if p.pos_idx is None:
                continue
            body, _ = split_eol(lines[p.pos_idx])
            page_token, rest = parse_position(body.split("=", 1)[1])
            rest = [str(p.x), str(p.y)] + rest[2:]
            lines[p.pos_idx] = replace_value(lines[p.pos_idx], ",".join([page_token] + rest))
        main_page = (t.page or DEFAULT_PAGE).upper()
        bound = next((p for p in t.placements if p.page == main_page), t.placements[0])
        if t.left_idx is not None:
            lines[t.left_idx] = replace_value(lines[t.left_idx], str(bound.x))
        if t.top_idx is not None:
            lines[t.top_idx] = replace_value(lines[t.top_idx], str(bound.y))
    for rel in doc.relation_list:
        apply_relation(lines, rel, doc.main_page)
    return lines


def encode(lines: list[str], encoding: str, bom: bool) -> bytes:
    data = "".join(lines).encode(encoding)
    return UTF8_BOM + data if bom else data


def layout_bytes(data: bytes, opt: Options | None = None) -> tuple[bytes, int, int, int]:
    """整列後のバイト列・テーブル数・ページ数・線を引けなかった Relation の数を返す。"""
    opt = opt or Options()
    doc = parse(data)
    if not doc.tables:
        raise ValueError("テーブル（[Entity]）が見つかりません。.a5er ファイルか確認してください。")
    page_count = arrange(doc, opt)
    return encode(apply(doc), doc.encoding, doc.bom), len(doc.tables), page_count, doc.unrouted


def summary(n_tables: int, n_pages: int, unrouted: int) -> str:
    text = f"整列しました: テーブル {n_tables} 個 / ページ {n_pages}"
    if unrouted:
        text += f" / 重ならずに線を引けなかったリレーション {unrouted} 本（線は元のまま）"
    return text


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def default_output(path: str) -> str:
    base, ext = os.path.splitext(path)
    return f"{base}_layout{ext or '.a5er'}"


def add_layout_options(ap: argparse.ArgumentParser) -> None:
    ap.add_argument("--mode", choices=["compact", "layered", "grid"], default="compact",
                    help="compact=線が重ならないよう正方形に近くまとめる（既定） / layered=親を上・子を下に段組み / grid=名前順の格子")
    ap.add_argument("--gap-x", type=int, default=60, help="横の間隔（既定 60）")
    ap.add_argument("--gap-y", type=int, default=100, help="縦の間隔の最小値（既定 100）")
    ap.add_argument("--max-width", type=int, default=3000, help="折り返す横幅（既定 3000）")
    ap.add_argument("--margin", type=int, default=50, help="左上の余白（既定 50）")
    ap.add_argument("--keep-lines", action="store_true", help="リレーション線（Bar1〜3）を書き換えない")


def options_from(args: argparse.Namespace) -> Options:
    return Options(args.mode, args.gap_x, args.gap_y, args.max_width, args.margin, args.keep_lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="A5:SQL Mk-2 の ER 図（.a5er）のテーブル配置を自動で整列します。")
    ap.add_argument("input", help="入力 .a5er ファイル")
    ap.add_argument("-o", "--output", help="出力先（既定: 入力名_layout.a5er）")
    add_layout_options(ap)
    args = ap.parse_args(argv)

    src = args.input
    dst = args.output or default_output(src)
    if not os.path.isfile(src):
        print(f"エラー: ファイルがありません: {src}", file=sys.stderr)
        return 1
    if os.path.abspath(dst) == os.path.abspath(src):
        print("エラー: 出力先に入力ファイルと同じパスは指定できません。", file=sys.stderr)
        return 1

    try:
        with open(src, "rb") as f:
            out, n_tables, n_pages, unrouted = layout_bytes(f.read(), options_from(args))
    except (ValueError, UnicodeDecodeError) as e:
        print(f"エラー: {e}", file=sys.stderr)
        return 1
    with open(dst, "wb") as f:
        f.write(out)
    print(f"{summary(n_tables, n_pages, unrouted)} / 出力: {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
