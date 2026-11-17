#!/usr/bin/env python3
"""
OMR sheet layout calibration tool.
Mirrors the detection pipeline implemented in the Android app (OpenCV):
  1. Find 4 corner fiducial squares (Otsu + contour + solidity/fill/aspect filters)
  2. Warp perspective to the fiducial quad
  3. Detect all printed bubbles (adaptive threshold + circular contour filter)
  4. Cluster bubbles into blocks, estimate rows/cols/origin/pitch per block
  5. (For "tc*" filled sheets) measure fill scores to validate thresholds

Usage:  python3 calibrate_layout.py <image1.png> [image2.png ...]
Output: human-readable block report + normalized coords (JSON) per image.
"""
import cv2
import numpy as np
import json
import math
import sys
import os
from collections import defaultdict


# ---------------------------------------------------------------- fiducials
def remove_long_lines(bin_, W):
    """Detach fiducials that touch the page border / table lines."""
    L = max(60, int(W * 0.06))
    hk = cv2.getStructuringElement(cv2.MORPH_RECT, (L, 1))
    vk = cv2.getStructuringElement(cv2.MORPH_RECT, (1, L))
    hlines = cv2.morphologyEx(bin_, cv2.MORPH_OPEN, hk)
    vlines = cv2.morphologyEx(bin_, cv2.MORPH_OPEN, vk)
    lines = cv2.bitwise_or(hlines, vlines)
    return cv2.bitwise_and(bin_, cv2.bitwise_not(lines))


def find_fiducials(gray):
    blur = cv2.GaussianBlur(gray, (3, 3), 0)
    # Otsu can drift high on gray-background scans -> cap it: fiducials are near-black
    otsu, _ = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    t = min(otsu, 130.0)
    _, bin_ = cv2.threshold(blur, t, 255, cv2.THRESH_BINARY_INV)
    bin_ = cv2.morphologyEx(bin_, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    bin_ = remove_long_lines(bin_, gray.shape[1])
    cnts, _ = cv2.findContours(bin_, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    H, W = gray.shape
    min_a, max_a = 0.00008 * W * H, 0.02 * W * H
    out = []
    for c in cnts:
        a = cv2.contourArea(c)
        if a < min_a or a > max_a:
            continue
        x, y, w, h = cv2.boundingRect(c)
        ar = w / max(h, 1)
        if not (0.7 < ar < 1.4):
            continue
        hull = cv2.convexHull(c)
        sol = a / max(cv2.contourArea(hull), 1)
        fill = a / (w * h)
        if sol > 0.95 and fill > 0.8:
            M = cv2.moments(c)
            out.append((M["m10"] / M["m00"], M["m01"] / M["m00"], w, h))
    return out


def assemble_quad(pts):
    """Return [TL, TR, BR, BL] from candidate centers, or None."""
    if len(pts) < 4:
        return None
    tl = min(pts, key=lambda p: p[0] + p[1])
    br = max(pts, key=lambda p: p[0] + p[1])
    tr = max(pts, key=lambda p: p[0] - p[1])
    bl = min(pts, key=lambda p: p[0] - p[1])
    ids = {id(tl), id(tr), id(br), id(bl)}
    if len(ids) < 4:
        return None
    return [tl[:2], tr[:2], br[:2], bl[:2]]


def warp_sheet(gray, quad, out_w=1400):
    tl, tr, br, bl = quad
    d = lambda p, q: math.hypot(p[0] - q[0], p[1] - q[1])
    w_top, w_bot = d(tl, tr), d(bl, br)
    h_l, h_r = d(tl, bl), d(tr, br)
    W = (w_top + w_bot) / 2
    Hh = (h_l + h_r) / 2
    out_h = round(out_w * Hh / W)
    src = np.float32(quad)
    dst = np.float32([[0, 0], [out_w - 1, 0], [out_w - 1, out_h - 1], [0, out_h - 1]])
    M = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(gray, M, (out_w, out_h), flags=cv2.INTER_CUBIC)
    return warped, out_h / out_w


# ---------------------------------------------------------------- bubbles
def detect_circles(gray):
    H, W = gray.shape
    bin_ = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 7
    )
    cnts, _ = cv2.findContours(bin_, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    r_min, r_max = W / 140.0, W / 30.0  # g12 P1 bubbles are small (d ~ W/64)
    out = []
    for c in cnts:
        a = cv2.contourArea(c)
        if a < math.pi * r_min * r_min or a > math.pi * r_max * r_max:
            continue
        p = cv2.arcLength(c, True)
        circ = 4 * math.pi * a / max(p * p, 1)
        if circ < 0.72:  # sample-12 bubbles are tall ellipses (circ ~0.79)
            continue
        x, y, w, h = cv2.boundingRect(c)
        if not (0.60 < w / max(h, 1) < 1.45):
            continue
        # solid squares (decorative markers) have fill ~1.0; circles/ellipses ~0.785
        if a / (w * h) > 0.88:
            continue
        M = cv2.moments(c)
        out.append((M["m10"] / M["m00"], M["m01"] / M["m00"], (w + h) / 4))
    return out


def cluster_blocks(circles, eps):
    n = len(circles)
    parent = list(range(n))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    pts = np.array([(c[0], c[1]) for c in circles])
    # grid bucket to keep it fast
    cell = eps
    buckets = defaultdict(list)
    for i, p in enumerate(pts):
        buckets[(int(p[0] // cell), int(p[1] // cell))].append(i)
    for i, p in enumerate(pts):
        bx, by = int(p[0] // cell), int(p[1] // cell)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for j in buckets.get((bx + dx, by + dy), ()):
                    if j <= i:
                        continue
                    q = pts[j]
                    if (p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 <= eps * eps:
                        ri, rj = find(i), find(j)
                        if ri != rj:
                            parent[ri] = rj
    groups = defaultdict(list)
    for i in range(n):
        groups[find(i)].append(circles[i])
    return list(groups.values())


def split_1d(vals, gap):
    vals = sorted(vals)
    groups = [[vals[0]]]
    for v in vals[1:]:
        if v - groups[-1][-1] > gap:
            groups.append([v])
        else:
            groups[-1].append(v)
    return [sum(g) / len(g) for g in groups]


def block_info(block):
    rs = [c[2] for c in block]
    med_r = sorted(rs)[len(rs) // 2]
    cols = split_1d([c[0] for c in block], med_r * 1.5)
    rows = split_1d([c[1] for c in block], med_r * 1.5)
    px = np.median(np.diff(cols)) if len(cols) > 1 else 0.0
    py = np.median(np.diff(rows)) if len(rows) > 1 else 0.0
    return {
        "rows": len(rows),
        "cols": len(cols),
        "origin": (cols[0], rows[0]),
        "pitch": (float(px), float(py)),
        "r_med": med_r,
        "bbox": (min(c[0] for c in block), min(c[1] for c in block),
                 max(c[0] for c in block), max(c[1] for c in block)),
    }


# ---------------------------------------------------------------- fill scores
def fill_score(gray, cx, cy, r):
    """(ring - inner) / ring  — darkness of bubble interior vs paper around it."""
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
    im, rm = inner.mean(), ring.mean()
    return max(0.0, (rm - im) / max(rm, 1.0))


# ---------------------------------------------------------------- main
def analyze(path):
    img = cv2.imread(path)
    if img is None:
        print(f"[!] cannot read {path}")
        return
    gray0 = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    H0, W0 = gray0.shape
    fids = find_fiducials(gray0)
    print(f"\n================ {os.path.basename(path)}  ({W0}x{H0}) ================")
    print(f"fiducial candidates: {len(fids)} -> {[(round(x),round(y)) for x,y,w,h in fids]}")
    quad = assemble_quad(fids)
    if quad is None:
        print("[!] quad not found")
        return
    d = lambda p, q: math.hypot(p[0] - q[0], p[1] - q[1])
    tilt = math.degrees(math.atan2(quad[1][1] - quad[0][1], quad[1][0] - quad[0][0]))
    print(f"quad TL{tuple(round(v) for v in quad[0])} TR{tuple(round(v) for v in quad[1])} "
          f"BR{tuple(round(v) for v in quad[2])} BL{tuple(round(v) for v in quad[3])}  tilt={tilt:.2f}deg")

    warped, aspect = warp_sheet(gray0, quad)
    WH, WW = warped.shape
    print(f"warped: {WW}x{WH}  aspect(h/w)={aspect:.4f}")

    circles = detect_circles(warped)
    print(f"circles detected: {len(circles)}  (r median={sorted(c[2] for c in circles)[len(circles)//2]:.1f}px)")
    r_med = sorted(c[2] for c in circles)[len(circles) // 2]
    blocks = cluster_blocks(circles, r_med * 3.2)
    blocks = [b for b in blocks if len(b) >= 6]

    infos = []
    for b in blocks:
        info = block_info(b)
        info["count"] = len(b)
        infos.append(info)
    # sort top-to-bottom, left-to-right by bbox
    infos.sort(key=lambda i: (round(i["bbox"][1] / (r_med * 4)), i["bbox"][0]))

    report = []
    for i, info in enumerate(infos):
        ox, oy = info["origin"]
        px, py = info["pitch"]
        rep = {
            "idx": i,
            "rows": info["rows"],
            "cols": info["cols"],
            "origin_nx": round(ox / WW, 5),
            "origin_ny": round(oy / WH, 5),
            "pitch_nx": round(px / WW, 5),
            "pitch_ny": round(py / WH, 5),
            "r_n": round(info["r_med"] / WW, 5),
            "count": info["count"],
            "bbox_n": [round(v / WW, 4) if k % 2 == 0 else round(v / WH, 4)
                       for k, v in enumerate(info["bbox"])],
        }
        report.append(rep)
        print(f"  blk{i:02d} rows={info['rows']:2d} cols={info['cols']:2d} "
              f"origin=({ox:6.1f},{oy:6.1f}) pitch=({px:5.1f},{py:5.1f}) r={info['r_med']:4.1f} n={info['count']}")

    # fill analysis (interesting for tc* sheets)
    scored = [(c, fill_score(warped, c[0], c[1], c[2])) for c in circles]
    vals = [s for _, s in scored]
    hist = [0] * 10
    for v in vals:
        hist[min(9, int(v * 10))] += 1
    print(f"fill-score histogram (x0.1 bins): {hist}")
    filled = [(c, s) for c, s in scored if s > 0.30]
    print(f"filled (>0.30): {len(filled)}")
    # group filled by block
    for i, info in enumerate(infos):
        bx0, by0, bx1, by1 = info["bbox"]
        m = r_med * 1.2
        sel = [c for c, s in filled if bx0 - m <= c[0] <= bx1 + m and by0 - m <= c[1] <= by1 + m]
        if sel:
            print(f"    blk{i:02d}: {len(sel)} filled")

    json.dump(report, open(path + ".blocks.json", "w"), indent=1)
    print(f"  -> wrote {path}.blocks.json")

    # debug visualization
    vis = cv2.cvtColor(warped, cv2.COLOR_GRAY2BGR)
    for c in circles:
        cv2.circle(vis, (int(c[0]), int(c[1])), int(c[2]), (0, 200, 0), 1)
    for info in infos:
        x0, y0, x1, y1 = info["bbox"]
        cv2.rectangle(vis, (int(x0), int(y0)), (int(x1), int(y1)), (255, 0, 0), 2)
    for c, s in filled:
        cv2.circle(vis, (int(c[0]), int(c[1])), int(c[2]) + 3, (0, 0, 255), 2)
    dbg = os.path.basename(path) + ".debug.png"
    cv2.imwrite("/tmp/" + dbg, vis)
    print(f"  -> wrote /tmp/{dbg}")


for p in sys.argv[1:]:
    analyze(p)
