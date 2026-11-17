#!/usr/bin/env python3
"""
Validate the pipeline end-to-end on the two master templates:
  1. Config -> nearest detected circle distance (snap quality)
  2. Synthetic pencil fills -> fill-score separation (threshold choice)
Also emits the final normalized config as JSON (single source of truth for Kotlin).
"""
import cv2
import numpy as np
import math
import json
import sys

sys.path.insert(0, "/home/dell/Downloads/appscan/src/tools")
from calibrate_layout import warp_sheet, detect_circles, find_fiducials, assemble_quad


def load(path):
    img = cv2.imread(path)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    quad = assemble_quad(find_fiducials(gray))
    warped, aspect = warp_sheet(gray, quad)
    return warped, warped.shape[1], warped.shape[0]


def build_m1011(W, H):
    """All bubbles as (section, group_idx, item_idx, col_idx, x_px, y_px)."""
    B = []
    def add(sec, g, i, col, x, y):
        B.append(dict(sec=sec, g=g, i=i, col=col, x=x / W, y=y / H))
    # ma de 3x10
    for r in range(10):
        for c in range(3):
            add("maDe", 0, r, c, 1277.53 + 34.08 * c, 59.76 + 34.10 * r)
    # P1 3 bands x 4 cols x 10 rows
    for b, y0 in enumerate([855.57, 1230.64, 1605.43]):
        for r in range(10):
            for c in range(4):
                add("p1", b * 10 + r, r, c, 67.82 + 45.88 * c, y0 + 34.09 * r)
    # P2 2 bands x 5 cau: (dungX, saiX) pairs
    pairs = [(403.45, 476.48), (618.78, 691.82), (834.69, 907.43),
             (1050.16, 1123.23), (1265.61, 1338.66)]
    for band, y0 in enumerate([540.09, 764.58]):
        for q in range(5):
            for r in range(4):
                add("p2", band * 5 + q, r, 0, pairs[q][0], y0 + 34.06 * r)
                add("p2", band * 5 + q, r, 1, pairs[q][1], y0 + 34.06 * r)
    # P3 2 bands x 5 cau: minus(col -1), comma(cols 1,2), digits 10x4
    for band, (ym, yc, yd) in enumerate([(1035.62, 1069.57, 1103.8),
                                         (1537.21, 1571.51, 1605.43)]):
        for q, x0 in enumerate([362, 578, 793, 1009, 1225]):
            add("p3minus", band * 5 + q, 0, 0, x0, ym)
            for cc in (1, 2):
                add("p3comma", band * 5 + q, cc, cc, x0 + 34.33 * cc, yc)
            for r in range(10):
                for c in range(4):
                    add("p3digit", band * 5 + q, r, c, x0 + 34.33 * c, yd + 34.10 * r)
    return B


def build_m12(W, H):
    B = []
    def add(sec, g, i, col, x, y):
        B.append(dict(sec=sec, g=g, i=i, col=col, x=x / W, y=y / H))
    for r in range(10):
        for c in range(8):
            add("sbd", 0, r, c, 993.87 + 28.787 * c, 204.29 + 41.746 * r)
    for r in range(10):
        for c in range(4):
            add("maDe", 0, r, c, 1252.98 + 28.857 * c, 204.45 + 41.82 * r)
    # P1 4 blocks
    for b, x0 in enumerate([114, 449.5, 785, 1120.5]):
        for r in range(10):
            for c in range(4):
                add("p1", b * 10 + r, r, c, x0 + 59.33 * c, 740.8 + 35.735 * r)
    # P2 8 cau x (dung,sai) x 4 rows
    pairs = [(114.17, 172.9), (232.9, 292.55), (448.52, 508.95), (568.16, 627.94),
             (785.02, 844.43), (904.72, 963.7), (1120.1, 1179.75), (1239.04, 1299.36)]
    for q, (dx, sx) in enumerate(pairs):
        for r in range(4):
            add("p2", q, r, 0, dx, 1243.46 + 35.73 * r)
            add("p2", q, r, 1, sx, 1243.46 + 35.73 * r)
    # P3 6 cau
    for q, x0 in enumerate([106, 320, 535, 749, 963, 1178]):
        add("p3minus", q, 0, 0, x0, 1513.37)
        for cc in (1, 2):
            add("p3comma", q, cc, cc, x0 + 35.67 * cc, 1553.22)
        for r in range(10):
            for c in range(4):
                add("p3digit", q, r, c, x0 + 35.67 * c, 1593.06 + 39.806 * r)
    return B


def fill_score(gray, cx, cy, r):
    r = max(r, 3.0)
    x0, x1 = int(cx - 2 * r), int(cx + 2 * r)
    y0, y1 = int(cy - 2 * r), int(cy + 2 * r)
    if x0 < 0 or y0 < 0 or x1 >= gray.shape[1] or y1 >= gray.shape[0]:
        return 0.0
    roi = gray[y0:y1, x0:x1].astype(np.float32)
    yy, xx = np.mgrid[y0:y1, x0:x1]
    d = np.hypot(xx - cx, yy - cy)
    inner = roi[d <= r * 0.55]
    ring = roi[(d >= r * 1.15) & (d <= r * 1.45)]
    if inner.size == 0 or ring.size == 0:
        return 0.0
    return max(0.0, (ring.mean() - inner.mean()) / max(ring.mean(), 1.0))


def main():
    results = {}
    for name, path, builder in [
        ("m1011", "/tmp/g1011.png", build_m1011),
        ("m12", "/tmp/g12.png", build_m12),
    ]:
        warped, W, H = load(path)
        circles = detect_circles(warped)
        bubbles = builder(W, H)

        # --- snap validation
        cxy = np.array([(c[0], c[1]) for c in circles])
        maxd, ds = 0, []
        for b in bubbles:
            px, py = b["x"] * W, b["y"] * H
            d = np.min(np.hypot(cxy[:, 0] - px, cxy[:, 1] - py))
            ds.append(d)
            maxd = max(maxd, d)
        ds = np.array(ds)
        print(f"\n[{name}] bubbles={len(bubbles)} snap dist: mean={ds.mean():.2f} "
              f"p95={np.percentile(ds,95):.2f} max={maxd:.2f} (pitch ~28-46px)")

        # --- baseline fill scores (empty template)
        base = []
        for b in bubbles:
            px, py = b["x"] * W, b["y"] * H
            d = np.min(np.hypot(cxy[:, 0] - px, cxy[:, 1] - py))
            r = circles[int(np.argmin(np.hypot(cxy[:, 0] - px, cxy[:, 1] - py)))][2] if d < 15 else 12.0
            base.append((b["sec"], fill_score(warped, px, py, r)))
        from collections import defaultdict
        by_sec = defaultdict(list)
        for sec, s in base:
            by_sec[sec].append(s)
        print(f"[{name}] EMPTY-template fill scores per section "
              f"(max / mean):")
        for sec, vals in by_sec.items():
            vals = np.array(vals)
            print(f"   {sec:9s} max={vals.max():.3f} mean={vals.mean():.3f} p99={np.percentile(vals,99):.3f}")

        # --- synthetic fills
        rng = np.random.default_rng(42)
        filled_scores, empty_scores = [], []
        sim = warped.copy()
        test = []
        for b in bubbles:
            px, py = b["x"] * W, b["y"] * H
            d = np.min(np.hypot(cxy[:, 0] - px, cxy[:, 1] - py))
            r = circles[int(np.argmin(np.hypot(cxy[:, 0] - px, cxy[:, 1] - py)))][2] if d < 15 else 12.0
            test.append((b, px, py, r))
        picks = rng.choice(len(test), size=int(len(test) * 0.12), replace=False)
        for idx in picks:
            b, px, py, r = test[idx]
            g = int(rng.choice([70, 100, 135]))
            rw = r * rng.uniform(0.75, 1.05)
            rh = r * rng.uniform(0.8, 1.1) * (1.35 if b["sec"] in ("sbd", "maDe", "p1") and name == "m12" else 1.0)
            cv2.ellipse(sim, (int(px), int(py)), (int(rw), int(rh)), 0, 0, 360, g, -1)
        for idx, (b, px, py, r) in enumerate(test):
            s = fill_score(sim, px, py, r)
            (filled_scores if idx in set(picks.tolist()) else empty_scores).append((b["sec"], s))
        fs = np.array([s for _, s in filled_scores])
        es = np.array([s for _, s in empty_scores])
        print(f"[{name}] SIM: filled n={len(fs)} min={fs.min():.3f} p05={np.percentile(fs,5):.3f} | "
              f"empty max={es.max():.3f} p99={np.percentile(es,99):.3f}")
        results[name] = dict(
            bubbles=[{k: (round(v, 6) if isinstance(v, float) else v) for k, v in b.items()} for b in bubbles])
    json.dump(results, open("/tmp/omr_config.json", "w"))
    print("\nwrote /tmp/omr_config.json")


if __name__ == "__main__":
    main()
