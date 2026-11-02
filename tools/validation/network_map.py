"""Map of a corridor with both directions colored by the estimated density at one time.

Usage: python3 tools/validation/network_map.py <output PNG> --direction LABEL GEOMETRY CELLS STATIONS [--direction ...]
                                               [--start HH:MM] [--time HH:MM] [--context GEOMETRY ...] [--title "..."]
  GEOMETRY  written by Validation with -Dgeometry_out: the road's points and their distance along the corridor
  CELLS     written by Validation with -Dcells_out: the estimate in every cell every 30 seconds
  STATIONS  the output CSV of the same run (station "none"): the detector stations, drawn as dots
  --start   clock time of time 0 of the data (default 15:00)
  --time    clock time to draw; by default the 5 minutes with the longest congested length (density above the critical
            density) over all directions
  --context other corridors' geometry files, drawn in light gray for orientation

Each direction is drawn offset to its right, the side it is on, so that both directions are visible. The color is the
density averaged over 5 minutes, from light (free flow) to dark (jam).
"""
import argparse
import csv
import math

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
from matplotlib.colors import LinearSegmentedColormap

# sequential blue ramp; the lightest step stays visible on the white map
BLUES = ['#b7d3f6', '#86b6ef', '#5598e7', '#2a78d6', '#1c5cab', '#104281', '#0d366b']
INK, MUTED, CONTEXT, SURFACE = '#0b0b0b', '#52514e', '#dcdbd7', '#fcfcfb'
CRITICAL_DENSITY = 2200 / 69  # veh/mi/lane, where the free-flow and congested branches meet
MAX_DENSITY = 100  # veh/mi/lane at the dark end of the scale
WINDOW = 300  # s averaged


def read_geometry(path):
    rows = [(float(r['position (mi)']), float(r['lat']), float(r['lon'])) for r in csv.DictReader(open(path))]
    # repeated points where links meet
    out = []
    for r in rows:
        if not out or (r[1], r[2]) != (out[-1][1], out[-1][2]):
            out.append(r)
    return out


def read_cells(path):
    cells = {}
    for r in csv.DictReader(open(path)):
        c = cells.setdefault(int(r['cell']), {'position': float(r['position (mi)']), 'k': {}})
        c['k'][int(r['time'])] = float(r['EKF k'])
    return cells


class Projection:
    """local equirectangular projection to miles"""
    def __init__(self, lat0):
        self.kx = 69.17 * math.cos(math.radians(lat0))
        self.ky = 69.17

    def __call__(self, lat, lon):
        return lon * self.kx, lat * self.ky


def point_at(geometry, position):
    """(lat, lon) at a distance along the corridor, interpolated between geometry points"""
    for a, b in zip(geometry, geometry[1:]):
        if a[0] <= position <= b[0]:
            f = (position - a[0]) / (b[0] - a[0]) if b[0] > a[0] else 0
            return a[1] + f * (b[1] - a[1]), a[2] + f * (b[2] - a[2])
    end = geometry[0] if position < geometry[0][0] else geometry[-1]
    return end[1], end[2]


def piece(geometry, start, end, project):
    """projected points of the corridor between two distances along it"""
    pts = [point_at(geometry, start)] + [(g[1], g[2]) for g in geometry if start < g[0] < end] + [point_at(geometry, end)]
    return [project(lat, lon) for lat, lon in pts]


def offset(points, d):
    """the polyline moved d miles to its right"""
    out = []
    for i, (x, y) in enumerate(points):
        a = points[max(i - 1, 0)]
        b = points[min(i + 1, len(points) - 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy) or 1
        out.append((x + d * dy / n, y - d * dx / n))
    return out


def window_mean(values, t):
    v = [k for s, k in values.items() if t <= s < t + WINDOW]
    return sum(v) / len(v) if v else float('nan')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('output')
    p.add_argument('--direction', nargs=4, action='append', required=True, metavar=('LABEL', 'GEOMETRY', 'CELLS', 'STATIONS'))
    p.add_argument('--start', default='15:00')
    p.add_argument('--time')
    p.add_argument('--context', nargs='*', default=[])
    p.add_argument('--title', default='')
    a = p.parse_args()

    hour, minute = (int(x) for x in a.start.split(':'))
    t0 = hour * 3600 + minute * 60
    directions = []
    for label, geometry, cells, stations in a.direction:
        cells = read_cells(cells)
        station_cells = {int(r['cell']) for r in csv.DictReader(open(stations))}
        directions.append({'label': label, 'geometry': read_geometry(geometry), 'cells': cells, 'stations': station_cells})

    # the time to draw: the 5 minutes with the longest congested length, after the first 30 minutes
    times = sorted(next(iter(directions[0]['cells'].values()))['k'])
    if a.time:
        h, m = (int(x) for x in a.time.split(':'))
        t_draw = h * 3600 + m * 60 - t0
    else:
        best = (-1, 0)
        for t in range(1800, times[-1] - WINDOW + 31, 60):
            length = 0
            for d in directions:
                order = sorted(d['cells'])
                for i, c in enumerate(order):
                    cell_len = (d['cells'][order[1]]['position'] - d['cells'][order[0]]['position'])
                    if window_mean(d['cells'][c]['k'], t) > CRITICAL_DENSITY:
                        length += cell_len
            best = max(best, (length, -t))
        t_draw = -best[1]
    clock = t0 + t_draw
    clock_text = f'{clock // 3600 % 12 or 12}:{clock % 3600 // 60:02d} {"AM" if clock < 12 * 3600 else "PM"}'

    lat0 = directions[0]['geometry'][0][1]
    project = Projection(lat0)
    all_pts = [project(g[1], g[2]) for d in directions for g in d['geometry']]
    xs, ys = [q[0] for q in all_pts], [q[1] for q in all_pts]
    extent = max(max(xs) - min(xs), max(ys) - min(ys))
    shift = 0.008 * extent  # offset of each direction to its right, miles

    cmap = LinearSegmentedColormap.from_list('density', BLUES)
    pad = 0.06 * extent
    width, height = max(xs) - min(xs) + 2 * pad, max(ys) - min(ys) + 2 * pad
    # 7 in wide, or narrower so that the map is at most 6.5 in high
    map_width = min(7, 6.5 * width / height)
    fig, ax = plt.subplots(figsize=(max(map_width, 4.5), max(2.2, map_width * height / width) + 1.2), dpi=200,
                           facecolor=SURFACE)
    ax.set_facecolor(SURFACE)
    for path in a.context:
        g = read_geometry(path)
        ax.plot(*zip(*[project(q[1], q[2]) for q in g]), color=CONTEXT, linewidth=1, zorder=1, solid_capstyle='round')

    for d in directions:
        order = sorted(d['cells'])
        half = (d['cells'][order[1]]['position'] - d['cells'][order[0]]['position']) / 2
        segments, values = [], []
        for c in order:
            pos = d['cells'][c]['position']
            pts = offset(piece(d['geometry'], pos - half, pos + half, project), shift)
            segments.append(pts)
            values.append(window_mean(d['cells'][c]['k'], t_draw))
        lc = LineCollection(segments, cmap=cmap, norm=plt.Normalize(0, MAX_DENSITY), linewidths=3.2, capstyle='butt', zorder=2)
        lc.set_array(values)
        ax.add_collection(lc)
        for c in d['stations']:
            if c in d['cells']:
                x, y = offset(piece(d['geometry'], d['cells'][c]['position'] - 0.01, d['cells'][c]['position'] + 0.01, project), 2.6 * shift)[0]
                ax.plot(x, y, 'o', markersize=1.8, color=MUTED, zorder=3)
        # direction label near the start, on its side
        start = offset(piece(d['geometry'], d['cells'][order[0]]['position'], d['cells'][order[min(8, len(order) - 1)]]['position'], project), 5 * shift)
        ax.annotate(f"{d['label']} start", start[0], fontsize=7, color=MUTED, ha='center', va='center', zorder=4)

    ax.set_xlim(min(xs) - pad, max(xs) + pad)
    ax.set_ylim(min(ys) - 1.6 * pad, max(ys) + pad)
    ax.set_aspect('equal')
    ax.set_xticks([])
    ax.set_yticks([])
    for spine in ax.spines.values():
        spine.set_visible(False)
    # scale bar, at the lower right
    bar = 5 if extent > 15 else 2 if extent > 6 else 1
    x0, y0 = max(xs) + pad * 0.6 - bar, min(ys) - pad * 1.3
    ax.plot([x0, x0 + bar], [y0, y0], color=INK, linewidth=1.5)
    ax.text(x0 + bar / 2, y0 + 0.012 * extent, f'{bar} mi', ha='center', va='bottom', fontsize=7, color=INK)
    sm = plt.cm.ScalarMappable(norm=plt.Normalize(0, MAX_DENSITY), cmap=cmap)
    bar_ax = fig.colorbar(sm, ax=ax, orientation='horizontal', fraction=0.05, pad=0.06, aspect=40, shrink=0.7)
    bar_ax.set_label('Estimated density (veh/mi/lane), 5-minute average', color=INK, fontsize=8)
    bar_ax.ax.tick_params(colors=MUTED, labelsize=7)
    bar_ax.outline.set_edgecolor(CONTEXT)
    ax.set_title((f'{a.title}, {clock_text}' if a.title else clock_text) + '\n', loc='left', fontsize=10, color=INK)
    ax.text(0, 1.0, 'Each direction is drawn on its right-hand side. Dots: detector stations.', transform=ax.transAxes,
            fontsize=7, color=MUTED, va='bottom')
    fig.savefig(a.output, facecolor=SURFACE, bbox_inches='tight')
    print(f'{a.output}: {clock_text}')


if __name__ == '__main__':
    main()
