"""A5:SQL Mk-2 の ER 図ファイル（.a5er）と CSV の間で、論理名・リレーションをやり取りする。

    python a5er_csv.py export-names     入力.a5er [-o 出力.csv]
    python a5er_csv.py import-names     入力.a5er 論理名.csv [-o 出力.a5er]
    python a5er_csv.py export-relations 入力.a5er [-o 出力.csv]
    python a5er_csv.py import-relations 入力.a5er リレーション.csv [-o 出力.a5er]
    python a5er_csv.py all              入力.a5er [CSV ...] [-o 出力.a5er] [--keep-lines]

論理名はテーブル物理名＋カラム物理名の組み合わせで扱う（カラム名だけでは照合しない）。
"""

from __future__ import annotations

import argparse
import csv
import io
import os
import re
import sys
from dataclasses import dataclass, field

import a5er_layout
from a5er_layout import DEFAULT_PAGE, TABLE_SECTIONS, decode, encode, split_eol, split_lines

NAMES_HEADER = ["テーブル物理名", "カラム物理名", "論理名"]
RELATIONS_HEADER = ["親テーブル", "子テーブル", "親カラム", "子カラム", "親側種別", "子側種別", "依存"]

_FIRST_TOKEN_RE = re.compile(r'^("(?:[^"]|"")*"|[^,]*)(.*)$', re.S)


# ---------------------------------------------------------------------------
# .a5er の読み取り
# ---------------------------------------------------------------------------


@dataclass
class Column:
    idx: int
    logical: str
    physical: str


@dataclass
class Ent:
    pname: str = ""
    page: str = ""
    lname: str = ""
    pname_idx: int | None = None
    lname_idx: int | None = None
    columns: list[Column] = field(default_factory=list)

    def find_column(self, name: str) -> Column | None:
        key = name.strip().lower()
        return next((c for c in self.columns if c.physical.lower() == key), None)


@dataclass
class Rel:
    entity1: str = ""
    entity2: str = ""
    fields1: str = ""
    fields2: str = ""
    type1: str = ""
    type2: str = ""
    dependence: str = ""


@dataclass
class Model:
    lines: list[str]
    encoding: str
    bom: bool
    entities: list[Ent]
    relations: list[Rel]
    max_zorder: int

    def find_entities(self, name: str) -> list[Ent]:
        key = name.strip().lower()
        return [e for e in self.entities if e.pname.lower() == key]

    def eol(self) -> str:
        if self.lines:
            return split_eol(self.lines[0])[1] or "\n"
        return "\n"


def load_model(data: bytes) -> Model:
    text, encoding, bom = decode(data)
    lines = split_lines(text)
    entities: list[Ent] = []
    relations: list[Rel] = []
    max_z = 0
    ent: Ent | None = None
    rel: Rel | None = None
    rel_keys = {
        "Entity1": "entity1", "Entity2": "entity2", "Fields1": "fields1", "Fields2": "fields2",
        "RelationType1": "type1", "RelationType2": "type2", "Dependence": "dependence",
    }

    for i, line in enumerate(lines):
        body, _ = split_eol(line)
        stripped = body.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            section = stripped[1:-1]
            ent = rel = None
            if section in TABLE_SECTIONS:
                ent = Ent()
                entities.append(ent)
            elif section == "Relation":
                rel = Rel()
                relations.append(rel)
            continue
        if "=" not in body or body.startswith("#"):
            continue
        key, value = body.split("=", 1)
        key = key.strip()
        if key == "ZOrder":
            try:
                max_z = max(max_z, int(value.strip()))
            except ValueError:
                pass
        if ent is not None:
            if key == "PName":
                ent.pname, ent.pname_idx = value, i
            elif key == "LName":
                ent.lname, ent.lname_idx = value, i
            elif key == "Page":
                ent.page = value
            elif key == "Field":
                cols = next(csv.reader([value]), [])
                ent.columns.append(Column(i, cols[0] if cols else "", cols[1] if len(cols) > 1 else ""))
        elif rel is not None and key in rel_keys:
            setattr(rel, rel_keys[key], value)

    entities = [e for e in entities if e.pname]
    relations = [r for r in relations if r.entity1 and r.entity2]
    if not entities:
        raise ValueError("テーブル（[Entity]）が見つかりません。.a5er ファイルか確認してください。")
    return Model(lines, encoding, bom, entities, relations, max_z)


def set_value(line: str, value: str) -> str:
    body, eol = split_eol(line)
    return f"{body.split('=', 1)[0]}={value}{eol}"


def quote(s: str) -> str:
    return '"' + s.replace('"', '""') + '"'


def replace_first_token(line: str, new_text: str) -> str:
    body, eol = split_eol(line)
    key, value = body.split("=", 1)
    m = _FIRST_TOKEN_RE.match(value)
    return f"{key}={quote(new_text)}{m.group(2)}{eol}"


# ---------------------------------------------------------------------------
# CSV
# ---------------------------------------------------------------------------


def write_csv(rows: list[list[str]]) -> bytes:
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\r\n").writerows(rows)
    return buf.getvalue().encode("utf-8-sig")


def read_csv(data: bytes, header: list[str]) -> list[tuple[int, list[str]]]:
    """(CSV の行番号, 列) の一覧。ヘッダ行と空行は除く。"""
    for enc in ("utf-8-sig", "cp932"):
        try:
            text = data.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise ValueError("CSV の文字コードを読めません（UTF-8 か Shift_JIS で保存してください）。")
    reader = csv.reader(io.StringIO(text, newline=""))
    rows = []
    for row in reader:
        cells = [c.strip() for c in row]
        if reader.line_num == 1 and cells[: len(header)] == header:
            continue
        if not any(cells):
            continue
        cells += [""] * (len(header) - len(cells))
        rows.append((reader.line_num, cells))
    return rows


@dataclass
class Report:
    done: int = 0
    unchanged: int = 0
    skipped: list[str] = field(default_factory=list)

    def skip(self, line_no: int, reason: str) -> None:
        self.skipped.append(f"  {line_no} 行目: {reason}")


# ---------------------------------------------------------------------------
# 論理名
# ---------------------------------------------------------------------------


def export_names(model: Model) -> tuple[bytes, int]:
    rows = [NAMES_HEADER]
    for e in model.entities:
        rows.append([e.pname, "", e.lname])
        rows.extend([e.pname, c.physical, c.logical] for c in e.columns)
    return write_csv(rows), len(rows) - 1


def import_names(model: Model, csv_data: bytes) -> tuple[bytes, Report]:
    lines = list(model.lines)
    inserts: dict[int, str] = {}  # PName 行の後ろに足す LName 行
    rep = Report()
    for line_no, (table, column, logical, *_) in read_csv(csv_data, NAMES_HEADER):
        if not logical:
            continue
        ents = model.find_entities(table)
        if not ents:
            rep.skip(line_no, f"テーブル「{table}」がありません")
            continue
        if not column:
            for e in ents:
                if e.lname == logical:
                    rep.unchanged += 1
                    continue
                if e.lname_idx is not None:
                    lines[e.lname_idx] = set_value(lines[e.lname_idx], logical)
                else:
                    _, eol = split_eol(lines[e.pname_idx])
                    inserts[e.pname_idx] = f"LName={logical}{eol or model.eol()}"
                e.lname = logical
                rep.done += 1
            continue
        hits = [c for e in ents if (c := e.find_column(column))]
        if not hits:
            rep.skip(line_no, f"テーブル「{table}」にカラム「{column}」がありません")
            continue
        for c in hits:
            if c.logical == logical:
                rep.unchanged += 1
                continue
            lines[c.idx] = replace_first_token(lines[c.idx], logical)
            c.logical = logical
            rep.done += 1

    if inserts:
        out = []
        for i, line in enumerate(lines):
            out.append(line)
            if i in inserts:
                if not line.endswith("\n"):
                    out[-1] = line + model.eol()
                out.append(inserts[i])
        lines = out
    return encode(lines, model.encoding, model.bom), rep


# ---------------------------------------------------------------------------
# リレーション
# ---------------------------------------------------------------------------


def split_fields(s: str) -> list[str]:
    return [f.strip() for f in s.split(",") if f.strip()]


def relation_key(parent: str, child: str, f1: list[str], f2: list[str]) -> tuple:
    return (parent.lower(), child.lower(), tuple(f.lower() for f in f1), tuple(f.lower() for f in f2))


def export_relations(model: Model) -> tuple[bytes, int]:
    rows = [RELATIONS_HEADER]
    for r in model.relations:
        rows.append([r.entity1, r.entity2, r.fields1, r.fields2, r.type1, r.type2, r.dependence])
    return write_csv(rows), len(rows) - 1


def relation_block(parent: Ent, child: Ent, f1: list[str], f2: list[str],
                   t1: str, t2: str, dep: str, zorder: int, eol: str) -> list[str]:
    page = parent.page or DEFAULT_PAGE
    body = [
        "[Relation]",
        f"Entity1={parent.pname}",
        f"Entity2={child.pname}",
        f"RelationType1={t1 or '2'}",
        f"RelationType2={t2 or '3'}",
        f"Fields1={','.join(f1)}",
        f"Fields2={','.join(f2)}",
        "Cardinarity1=",
        "Cardinarity2=",
        f'Position={quote(page)},0,5000,5000,5000,R,R,""',
        f"Dependence={dep or '0'}",
        "Caption=",
        "PName=",
        "LineMode=0",
        "Bar1=500",
        "Bar2=500",
        "Bar3=500",
        "TermPos1=R",
        "TermPos2=R",
        f"ZOrder={zorder}",
    ]
    return [s + eol for s in body]


def import_relations(model: Model, csv_data: bytes) -> tuple[bytes, Report]:
    rep = Report()
    existing = {
        relation_key(r.entity1, r.entity2, split_fields(r.fields1), split_fields(r.fields2))
        for r in model.relations
    }
    eol = model.eol()
    new_lines: list[str] = []
    zorder = model.max_zorder

    for line_no, (p_name, c_name, p_cols, c_cols, t1, t2, dep, *_) in read_csv(csv_data, RELATIONS_HEADER):
        parents, children = model.find_entities(p_name), model.find_entities(c_name)
        if not parents:
            rep.skip(line_no, f"親テーブル「{p_name}」がありません")
            continue
        if not children:
            rep.skip(line_no, f"子テーブル「{c_name}」がありません")
            continue
        parent, child = parents[0], children[0]
        f1, f2 = split_fields(p_cols), split_fields(c_cols)
        if not f1 or not f2:
            rep.skip(line_no, "親カラム・子カラムが空です")
            continue
        if len(f1) != len(f2):
            rep.skip(line_no, "親カラムと子カラムの個数が違います")
            continue
        missing = [f"{parent.pname}.{f}" for f in f1 if not parent.find_column(f)]
        missing += [f"{child.pname}.{f}" for f in f2 if not child.find_column(f)]
        if missing:
            rep.skip(line_no, f"カラムがありません: {', '.join(missing)}")
            continue
        f1 = [parent.find_column(f).physical for f in f1]
        f2 = [child.find_column(f).physical for f in f2]
        key = relation_key(parent.pname, child.pname, f1, f2)
        if key in existing:
            rep.unchanged += 1
            continue
        existing.add(key)
        zorder += 1
        new_lines.append(eol)
        new_lines.extend(relation_block(parent, child, f1, f2, t1, t2, dep, zorder, eol))
        rep.done += 1

    lines = list(model.lines)
    if new_lines:
        if lines and not lines[-1].endswith("\n"):
            lines[-1] += eol
        if lines and not split_eol(lines[-1])[0].strip():
            new_lines = new_lines[1:]  # 末尾がすでに空行なら空行を足さない
        lines.extend(new_lines)
    return encode(lines, model.encoding, model.bom), rep


# ---------------------------------------------------------------------------
# 一括モード
# ---------------------------------------------------------------------------


def csv_kind(data: bytes) -> str:
    """CSV の種類（"names" / "relations"）をヘッダ行（無ければ列数）で見分ける。"""
    for enc in ("utf-8-sig", "cp932"):
        try:
            text = data.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise ValueError("CSV の文字コードを読めません（UTF-8 か Shift_JIS で保存してください）。")
    first = next((r for r in csv.reader(io.StringIO(text, newline="")) if any(c.strip() for c in r)), [])
    cells = [c.strip() for c in first]
    if cells[:1] == NAMES_HEADER[:1]:
        return "names"
    if cells[:1] == RELATIONS_HEADER[:1]:
        return "relations"
    return "names" if len(cells) <= 3 else "relations"


def run_all(a5er: bytes, csvs: dict[str, bytes], opt: a5er_layout.Options) -> tuple[bytes, list[str]]:
    """論理名の反映 → リレーションの追加 → 整列 を順に行う。(出力, 表示する行) を返す。"""
    log: list[str] = []
    data = a5er
    if "names" in csvs:
        data, rep = import_names(load_model(data), csvs["names"])
        log += report_lines(rep, "論理名を反映")
    if "relations" in csvs:
        data, rep = import_relations(load_model(data), csvs["relations"])
        log += report_lines(rep, "リレーションを追加")
    data, n_tables, n_pages, unrouted = a5er_layout.layout_bytes(data, opt)
    log.append(a5er_layout.summary(n_tables, n_pages, unrouted))
    return data, log


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def with_suffix(path: str, suffix: str, ext: str) -> str:
    return f"{os.path.splitext(path)[0]}_{suffix}{ext}"


def read_file(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read()


def report_lines(rep: Report, done_label: str) -> list[str]:
    lines = [f"{done_label} {rep.done} 件 / 変更なし {rep.unchanged} 件 / スキップ {len(rep.skipped)} 件"]
    if rep.skipped:
        lines.append("スキップした行:")
        lines += rep.skipped
    return lines


def print_report(rep: Report, done_label: str) -> None:
    print("\n".join(report_lines(rep, done_label)))


def main_all(args: argparse.Namespace) -> int:
    inputs = [args.a5er] + args.csv
    for path in inputs:
        if not os.path.isfile(path):
            print(f"エラー: ファイルがありません: {path}", file=sys.stderr)
            return 1
    dst = args.output or with_suffix(args.a5er, "all", ".a5er")
    if any(os.path.abspath(dst) == os.path.abspath(p) for p in inputs):
        print("エラー: 出力先に入力ファイルと同じパスは指定できません。", file=sys.stderr)
        return 1
    try:
        csvs: dict[str, bytes] = {}
        for path in args.csv:
            data = read_file(path)
            kind = csv_kind(data)
            label = "論理名" if kind == "names" else "リレーション"
            if kind in csvs:
                raise ValueError(f"{label}の CSV が2つ以上あります: {path}")
            csvs[kind] = data
            print(f"{os.path.basename(path)} → {label}の CSV")
        out, log = run_all(read_file(args.a5er), csvs, a5er_layout.options_from(args))
    except (ValueError, UnicodeDecodeError) as e:
        print(f"エラー: {e}", file=sys.stderr)
        return 1
    print("\n".join(log))
    with open(dst, "wb") as f:
        f.write(out)
    print(f"出力: {dst}")
    return 0


COMMANDS = {
    "export-names": ("names", ".csv", False),
    "import-names": ("names", ".a5er", True),
    "export-relations": ("relations", ".csv", False),
    "import-relations": ("relations", ".a5er", True),
}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="A5:SQL Mk-2 の .a5er と CSV で論理名・リレーションをやり取りします。")
    sub = ap.add_subparsers(dest="command", required=True)
    for name, (_, _, needs_csv) in COMMANDS.items():
        p = sub.add_parser(name)
        p.add_argument("a5er", help="入力 .a5er ファイル")
        if needs_csv:
            p.add_argument("csv", help="読み込む CSV ファイル")
        p.add_argument("-o", "--output", help="出力先")
    p = sub.add_parser("all", help="論理名の反映 → リレーションの追加 → 整列 を一括で行う")
    p.add_argument("a5er", help="入力 .a5er ファイル")
    p.add_argument("csv", nargs="*", help="論理名・リレーションの CSV（0〜2 個。種類はヘッダで見分ける）")
    p.add_argument("-o", "--output", help="出力先（既定: 入力名_all.a5er）")
    a5er_layout.add_layout_options(p)
    args = ap.parse_args(argv)
    if args.command == "all":
        return main_all(args)

    suffix, ext, needs_csv = COMMANDS[args.command]
    inputs = [args.a5er] + ([args.csv] if needs_csv else [])
    for path in inputs:
        if not os.path.isfile(path):
            print(f"エラー: ファイルがありません: {path}", file=sys.stderr)
            return 1
    dst = args.output or with_suffix(args.a5er, suffix, ext)
    if any(os.path.abspath(dst) == os.path.abspath(p) for p in inputs):
        print("エラー: 出力先に入力ファイルと同じパスは指定できません。", file=sys.stderr)
        return 1

    try:
        model = load_model(read_file(args.a5er))
        if args.command == "export-names":
            out, n = export_names(model)
            message = f"論理名を書き出しました: {n} 行"
        elif args.command == "export-relations":
            out, n = export_relations(model)
            message = f"リレーションを書き出しました: {n} 件"
        elif args.command == "import-names":
            out, rep = import_names(model, read_file(args.csv))
            print_report(rep, "論理名を反映")
            message = "論理名を読み込みました"
        else:
            out, rep = import_relations(model, read_file(args.csv))
            print_report(rep, "リレーションを追加")
            message = "リレーションを読み込みました"
    except (ValueError, UnicodeDecodeError) as e:
        print(f"エラー: {e}", file=sys.stderr)
        return 1

    with open(dst, "wb") as f:
        f.write(out)
    print(f"{message} / 出力: {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
