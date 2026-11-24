"""リレーション線の形の計算と、線を通す位置（Bar1〜3）の探索。

A5:SQL Mk-2 の線の決まり（実機で測定）:
- 向き: 高さの範囲が重なれば横向き、幅の範囲が重なれば縦向き。斜めなら中心どうしの上下差と左右差の大きいほう
- 縦向き: P の相手側の横の辺の x = P.左 + Bar1×P.幅
          → 中の横線 y = 上のテーブルの枠の下端 + Bar2×(下のテーブルの名前の文字の上端 − 上のテーブルの枠の下端)
             （名前の文字の上端 = 枠の上端 − 約 26）
          → C の相手側の横の辺の x = C.左 + Bar3×C.幅
          上から入る線は、枠の上端ではなくテーブル名の帯の上（枠の上端 − 約 60）で止まる
- 横向き: P の相手側の縦の辺の y = P.上 + Bar1×P.高さ → 中の縦線 x = 左のテーブルの右辺 + Bar2×(右のテーブルの左辺 − 左のテーブルの右辺)
          → C の相手側の縦の辺の y = C.上 + Bar3×C.高さ
"""

from __future__ import annotations

from dataclasses import dataclass

CLEARANCE = 10  # 線とほかのテーブルの間に空ける幅
PARALLEL_GAP = 15  # 同じ向きの線どうしの最小の間
END_MIN = 30  # 端の線の最短（線の端の記号の分）
TERMINAL_ZONE = 30  # 線の端の近くでは、ほかの線と交わらない
AMBIGUOUS = 40  # 斜めのとき、中心の上下差と左右差の差がこれ未満の配置は使わない（向きが入れ替わる危険）
OVERLAP_MARGIN = 20  # 幅・高さの範囲の重なりがこれ以内の配置は使わない（同上）
BEND_COST = 50
CROSS_COST = 400
STRAIGHT_STEP = 20
SELF_LOOP_RIGHT = 130
SELF_LOOP_TOP = 60
SEARCH_MARGIN = 3  # 探索では丸め誤差の分だけ間を広く取る

FRACTIONS_END = (0.5, 0.35, 0.65, 0.2, 0.8, 0.1, 0.9)
FRACTIONS_MID = (0.5, 0.35, 0.65, 0.2, 0.8)


@dataclass
class Box:
    """テーブルの枠と占有範囲（ラベル・自己参照の輪を含む）。"""

    x: float
    y: float
    w: float
    h: float
    label_h: float = 60  # テーブル名の帯の高さ（枠の上。上から入る線はここで止まる）
    text_h: float = 26  # テーブル名の文字の高さ（縦向きの Bar2 の基準）
    label_w: float = 0
    self_loop: bool = False

    @property
    def right(self) -> float:
        return self.x + self.w

    @property
    def top(self) -> float:
        """帯を含めた上端（上から入る線はここで止まる）。"""
        return self.y - self.label_h

    @property
    def bottom(self) -> float:
        return self.y + self.h

    def occupied(self) -> tuple[float, float, float, float]:
        # 自己参照の線は右へ約 100 伸びる（実機で確認）
        right = SELF_LOOP_RIGHT if self.self_loop else 0
        top = SELF_LOOP_TOP if self.self_loop else 0
        return (self.x, self.y - self.label_h - top,
                self.x + max(self.w, self.label_w) + right, self.y + self.h)


# 線分: (x1, y1, x2, y2)。必ず水平か垂直
Segment = tuple[float, float, float, float]


@dataclass
class Line:
    segments: list[Segment]  # 長さ 0 の線分は含めない
    start: tuple[float, float]
    end: tuple[float, float]


def gaps(p: Box, c: Box) -> tuple[float, float]:
    # 上下の隙間は、名前の文字の上端（枠の上端 − text_h）から測る（実機で確認）
    gx = max(c.x - p.right, p.x - c.right)
    gy = max(c.y - c.text_h - p.bottom, p.y - p.text_h - c.bottom)
    return gx, gy


def direction(p: Box, c: Box) -> str | None:
    """"v"（縦向き）/ "h"（横向き）/ None（枠が重なる・向きがあいまい）。
    A5:SQL Mk-2 の決まり（実機で確認）: 高さの範囲が重なれば横向き、幅の範囲が重なれば縦向き、
    どちらも重ならない（斜め）なら中心どうしの上下の差と左右の差の大きいほう。"""
    ox = min(p.right, c.right) - max(p.x, c.x)  # 幅の範囲の重なり（負なら離れている）
    oy = min(p.bottom, c.bottom) - max(p.y, c.y)  # 高さの範囲の重なり
    edge = OVERLAP_MARGIN
    if ox > edge and oy > edge:
        return None  # 枠が重なる
    if -edge <= ox <= edge or -edge <= oy <= edge:
        return None  # 重なるかどうかの境目（推定の大きさの誤差で変わりうる）
    gx, gy = gaps(p, c)
    if oy > edge:
        return "h" if gx > 0 else None
    if ox > edge:
        return "v" if gy > 0 else None
    dx = abs((p.x + p.w / 2) - (c.x + c.w / 2))
    dy = abs((p.y + p.h / 2) - (c.y + c.h / 2))
    if abs(dy - dx) < AMBIGUOUS:
        return None
    if dy > dx:
        return "v" if gy > 0 else None
    return "h" if gx > 0 else None


def line_points(p: Box, c: Box, bars: tuple[float, float, float], mode: str) -> list[tuple[float, float]]:
    b1, b2, b3 = bars
    if mode == "v":
        upper, lower = (p, c) if p.bottom <= c.top else (c, p)
        py = p.bottom if p is upper else p.top
        cy = c.bottom if c is upper else c.top
        px = p.x + b1 * p.w
        cx = c.x + b3 * c.w
        my = upper.bottom + b2 * (lower.y - lower.text_h - upper.bottom)
        return [(px, py), (px, my), (cx, my), (cx, cy)]
    left, right = (p, c) if p.right <= c.x else (c, p)
    px = p.right if p is left else p.x
    cx = c.right if c is left else c.x
    py = p.y + b1 * p.h
    cy = c.y + b3 * c.h
    mx = left.right + b2 * (right.x - left.right)
    return [(px, py), (mx, py), (mx, cy), (cx, cy)]


def to_line(points: list[tuple[float, float]]) -> Line:
    segs = []
    for (x1, y1), (x2, y2) in zip(points, points[1:]):
        if abs(x1 - x2) + abs(y1 - y2) >= 1:  # 丸めでできる 1 未満の段差は線として扱わない
            segs.append((x1, y1, x2, y2))
    return Line(segs, points[0], points[-1])


def seg_hits_rect(s: Segment, r: tuple[float, float, float, float], pad: float) -> bool:
    x1, y1, x2, y2 = s
    lo_x, hi_x = min(x1, x2), max(x1, x2)
    lo_y, hi_y = min(y1, y2), max(y1, y2)
    return lo_x < r[2] + pad and hi_x > r[0] - pad and lo_y < r[3] + pad and hi_y > r[1] - pad


def _near(pt: tuple[float, float], q: tuple[float, float]) -> bool:
    return abs(pt[0] - q[0]) + abs(pt[1] - q[1]) < TERMINAL_ZONE


def compare(a: Line, b: Line, gap: float = PARALLEL_GAP) -> int | None:
    """a と b の交差数。重なる（gap 未満に近づく）・端の近くで交わる場合は None。"""
    crosses = 0
    for s in a.segments:
        sh = abs(s[1] - s[3]) < 1e-6
        for t in b.segments:
            th = abs(t[1] - t[3]) < 1e-6
            if sh == th:
                if sh:
                    if abs(s[1] - t[1]) < gap and \
                            max(min(s[0], s[2]), min(t[0], t[2])) < min(max(s[0], s[2]), max(t[0], t[2])) + gap:
                        return None
                else:
                    if abs(s[0] - t[0]) < gap and \
                            max(min(s[1], s[3]), min(t[1], t[3])) < min(max(s[1], s[3]), max(t[1], t[3])) + gap:
                        return None
                continue
            h, v = (s, t) if sh else (t, s)
            x, y = v[0], h[1]
            if min(h[0], h[2]) <= x <= max(h[0], h[2]) and min(v[1], v[3]) <= y <= max(v[1], v[3]):
                pt = (x, y)
                if _near(pt, a.start) or _near(pt, a.end) or _near(pt, b.start) or _near(pt, b.end):
                    return None
                crosses += 1
    return crosses


def line_length(line: Line) -> float:
    return sum(abs(s[0] - s[2]) + abs(s[1] - s[3]) for s in line.segments)


FINE_END = tuple(i / 10 for i in range(1, 10)) + (0.05, 0.95, 0.25, 0.75)
FINE_MID = tuple(i / 10 for i in range(1, 10))


def _candidates(p: Box, c: Box, mode: str, fine: bool = False) -> list[tuple[float, float, float]]:
    out: list[tuple[float, float, float]] = []
    if mode == "v":
        lo, hi = max(p.x, c.x) + 10, min(p.right, c.right) - 10
        sp, sc = (p.x, p.w), (c.x, c.w)
    else:
        lo, hi = max(p.y, c.y) + 10, min(p.bottom, c.bottom) - 10
        sp, sc = (p.y, p.h), (c.y, c.h)
    if hi >= lo:
        mid = (lo + hi) / 2
        k = 0
        while True:
            vals = [mid] if k == 0 else [mid - k * STRAIGHT_STEP, mid + k * STRAIGHT_STEP]
            vals = [v for v in vals if lo <= v <= hi]
            if not vals and k > 0:
                break
            for v in vals:
                out.append(((v - sp[0]) / sp[1], 0.5, (v - sc[0]) / sc[1]))
            k += 1
    ends, mids = (FINE_END, FINE_MID) if fine else (FRACTIONS_END, FRACTIONS_MID)
    for b2 in mids:
        for b1 in ends:
            for b3 in ends:
                out.append((b1, b2, b3))
    return out


@dataclass
class Route:
    bars: tuple[float, float, float]
    line: Line
    cost: float


def route(p: Box, c: Box, obstacles: list[tuple[float, float, float, float]], lines: list[Line],
          fine: bool = False) -> Route | None:
    """p → c の線を、障害物（占有範囲）とほかの線を避けて探す。fine=True なら候補を細かくする。"""
    mode = direction(p, c)
    if mode is None:
        return None
    scored = []
    for bars in _candidates(p, c, mode, fine):
        pts = line_points(p, c, bars, mode)
        if mode == "v":
            # 中の横線が、上のテーブルの下端と下のテーブルの帯の上端の間に収まること
            upper_b = min(p.bottom, c.bottom) if p.bottom <= c.top else c.bottom
            lower_t = c.top if p.bottom <= c.top else p.top
            if not (upper_b + END_MIN <= pts[1][1] <= lower_t - END_MIN) and abs(pts[1][0] - pts[2][0]) >= 1e-6:
                continue
        # 端の線と中の線の長さ（線はまだ作らない。調べる順が来たら作る）
        e1 = abs(pts[0][0] - pts[1][0]) + abs(pts[0][1] - pts[1][1])
        em = abs(pts[1][0] - pts[2][0]) + abs(pts[1][1] - pts[2][1])
        e2 = abs(pts[2][0] - pts[3][0]) + abs(pts[2][1] - pts[3][1])
        if em < 1e-6:  # まっすぐな線
            if e1 + e2 < END_MIN * 2 + 10:
                continue
            base = e1 + e2
        else:
            if e1 < END_MIN or e2 < END_MIN:
                continue
            base = e1 + em + e2 + 2 * BEND_COST
        scored.append((base, bars, pts))
    scored.sort(key=lambda t: t[0])

    # 線は両端のテーブルを囲む範囲の中にしか引かれないので、そこにかかるものだけ調べる
    pad = CLEARANCE + SEARCH_MARGIN + PARALLEL_GAP + TERMINAL_ZONE
    area = (min(p.x, c.x) - pad, min(p.top, c.top) - pad,
            max(p.right, c.right) + pad, max(p.bottom, c.bottom) + pad)
    obstacles = [r for r in obstacles
                 if r[0] < area[2] and r[2] > area[0] and r[1] < area[3] and r[3] > area[1]]
    lines = [ln for ln in lines if any(seg_hits_rect(sg, area, 0) for sg in ln.segments)]

    best: Route | None = None
    clear = CLEARANCE + SEARCH_MARGIN
    for base, bars, pts in scored:
        if best is not None and base >= best.cost:
            break
        line = to_line(pts)
        hit = False
        for x1, y1, x2, y2 in line.segments:
            lx, hx = (x1, x2) if x1 < x2 else (x2, x1)
            ly, hy = (y1, y2) if y1 < y2 else (y2, y1)
            for r in obstacles:
                if lx < r[2] + clear and hx > r[0] - clear and ly < r[3] + clear and hy > r[1] - clear:
                    hit = True
                    break
            if hit:
                break
        if hit:
            continue
        crosses = 0
        ok = True
        for other in lines:
            n = compare(line, other, PARALLEL_GAP + SEARCH_MARGIN)
            if n is None:
                ok = False
                break
            crosses += n
        if not ok:
            continue
        cost = base + crosses * CROSS_COST
        if best is None or cost < best.cost:
            best = Route(bars, line, cost)
    return best


def rect_hits_lines(r: tuple[float, float, float, float], lines: list[Line]) -> bool:
    return any(seg_hits_rect(s, r, CLEARANCE) for line in lines for s in line.segments)


def bars_to_int(bars: tuple[float, float, float]) -> tuple[int, int, int]:
    return tuple(max(0, min(10000, round(b * 10000))) for b in bars)  # type: ignore[return-value]
