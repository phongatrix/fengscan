#!/usr/bin/env python3
"""Final extraction of bubble-grid configs (normalized to fiducial-quad warp)."""
import cv2
import numpy as np
import math
import sys

sys.path.insert(0, "/home/dell/Downloads/appscan/src/tools")
from calibrate_layout import warp_sheet, detect_circles, find_fiducials, assemble_quad


def load(path):
    img = cv2.imread(path)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    quad = assemble_quad(find_fiducials(gray))
    warped, aspect = warp_sheet(gray, quad)
    return warped, warped.shape[1], warped.shape[0]


def sel(circles, x0, y0, x1, y1):
    return [c for c in circles if x0 <= c[0] <= x1 and y0 <= c[1] <= y1]


def cluster_1d(vals, gap):
    vals = sorted(vals)
    groups = [[vals[0]]]
    for v in vals[1:]:
        if v - groups[-1][-1] > gap:
            groups.append([v])
        else:
            groups[-1].append(v)
    return [round(sum(g) / len(g), 2) for g in groups]


def rows_of(circles, gap=17):
    cs = sorted(circles, key=lambda c: c[1])
    groups = [[cs[0]]]
    for c in cs[1:]:
        if c[1] - groups[-1][-1][1] > gap:
            groups.append([c])
        else:
            groups[-1].append(c)
    return [(round(sum(x[1] for x in g) / len(g), 2), g) for g in groups]


def n(v, size):
    return round(v / size, 5)


def uni(vals):
    if len(vals) < 2:
        return 0.0
    d = np.diff(sorted(vals))
    return round(float(np.median(d)), 2)


def show_rows(label, rows, W, H):
    print(f"  {label}:")
    for y, cs in rows:
        xs = sorted(round(c[0]) for c in cs)
        print(f"    y={y} ({n(y,H)}) occ={len(cs)} xs={xs}")


def extract_1011():
    warped, W, H = load("/tmp/g1011.png")
    circles = detect_circles(warped)
    print(f"\n===== MAU 10-11 (warp {W}x{H}, {len(circles)} circles) =====")
    out = {}

    colx = cluster_1d([c[0] for c in sel(circles, 1230, 20, 1395, 400)], 12)
    rowy = cluster_1d([c[1] for c in sel(circles, 1230, 20, 1395, 400)], 12)
    out["maDe"] = (colx, rowy)
    print(f"  maDe: colx={colx} rowy={rowy}")

    for bi, (ylo, yhi) in enumerate([(820, 1190), (1195, 1565), (1570, 1973)]):
        cs = sel(circles, 30, ylo, 260, yhi)
        colx = cluster_1d([c[0] for c in cs], 25)
        rowy = cluster_1d([c[1] for c in cs], 25)
        out[f"p1_band{bi}"] = (colx, rowy)
        print(f"  p1_band{bi}: colx={colx} rowy={rowy[:3]}..{rowy[-1:]} py={uni(rowy)}")

    for bi, (ylo, yhi) in enumerate([(500, 745), (745, 985)]):
        cs = sel(circles, 350, ylo, 1370, yhi)
        colx = cluster_1d([c[0] for c in cs], 30)
        rowy = cluster_1d([c[1] for c in cs], 17)
        out[f"p2_band{bi}"] = (colx, rowy)
        print(f"  p2_band{bi}: colx={colx} rowy={rowy}")

    for bi, (ylo, yhi) in enumerate([(1010, 1520), (1530, 2000)]):
        cs = sel(circles, 340, ylo, 1385, min(yhi, H - 2))
        rr = rows_of(cs)
        show_rows(f"p3_band{bi}", rr, W, H)
        out[f"p3_band{bi}"] = rr
    return W, H, out


def extract_12():
    warped, W, H = load("/tmp/g12.png")
    circles = detect_circles(warped)
    print(f"\n===== MAU 12 (warp {W}x{H}, {len(circles)} circles) =====")
    out = {}

    cs = sel(circles, 800, 120, 1215, 640)
    colx = cluster_1d([c[0] for c in cs], 12)
    rowy = cluster_1d([c[1] for c in cs], 20)
    print(f"  sbd: colx={colx} rowy={rowy}")
    out["sbd"] = (colx, rowy)
    cs = sel(circles, 1215, 120, 1395, 640)
    colx = cluster_1d([c[0] for c in cs], 12)
    rowy = cluster_1d([c[1] for c in cs], 20)
    print(f"  maDe: colx={colx} rowy={rowy}")
    out["maDe"] = (colx, rowy)

    cs = sel(circles, 30, 640, 1320, 1200)
    colx = cluster_1d([c[0] for c in cs], 100)
    rowy = cluster_1d([c[1] for c in cs], 25)
    print(f"  p1: blocks_x={colx} rowy={rowy[:3]}..{rowy[-1:]} py={uni(rowy)}")
    out["p1"] = (colx, rowy)

    cs = sel(circles, 30, 1210, 1320, 1480)
    colx = cluster_1d([c[0] for c in cs], 30)
    rowy = cluster_1d([c[1] for c in cs], 17)
    print(f"  p2: colx={colx} rowy={rowy}")
    out["p2"] = (colx, rowy)

    cs = sel(circles, 30, 1490, 1395, H - 2)
    rr = rows_of(cs)
    show_rows("p3", rr, W, H)
    out["p3"] = rr
    return W, H, out


if __name__ == "__main__":
    extract_1011()
    extract_12()
