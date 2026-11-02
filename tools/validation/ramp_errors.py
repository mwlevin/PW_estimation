"""Space-time map of the flow error at withheld stations, next to the vehicle balance of each segment between stations
and the measurement status of each ramp, to show where the error comes from.

Usage: python3 tools/validation/ramp_errors.py <LOO folder> <detector data CSV> <stations list> <output PNG> [options]
  <LOO folder>      written by validate.sh: one CSV per withheld station
  <detector data>   the data the LOO runs used (for the vehicle balance)
  <stations list>   output of "Validation <data> 60 <dir> list" for both directions, each line prefixed with its
                    direction, e.g. "EB 3 cell 16 [7798+7799] (2 loops, 2.0 lanes) 2.123 mi"
Options:
  --inventory FILE  detector inventory (default data/MN610/MN610_Edited.csv)
  --ramps FILE      ramps file (default data/MN610/ramps.csv)
  --start HH:MM     clock time of time 0 (default 15:00)
  --title "..."

Left: the estimate's flow minus the measured flow at each station while it was withheld, in 5-minute averages (red: the
estimate is too high; blue: too low). Right, on the same distance axis: the average of that error at each station
(dots), and the imbalance of each segment between consecutive stations (bars): downstream station flow minus upstream
station flow minus measured entrance flows plus measured exit flows. With every ramp measured correctly, the imbalance
is near zero. A large imbalance is traffic that the detectors do not account for: a ramp without a detector, a ramp
detector without data, or a ramp or mainline detector that counts wrongly. Ramps are drawn at their positions: filled
triangles are measured, open triangles are not, and crosses are ramp detectors whose counts are implausible (an average
below 30 veh/hr, or only a bypass loop).
"""
import argparse
import csv
import glob
import math
import os
import re

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.lines import Line2D
from matplotlib.patches import Patch, Rectangle

# diverging blue <-> red with a neutral gray midpoint
BLUE = ['#0d366b', '#1c5cab', '#3987e5', '#86b6ef', '#cde2fb']
RED = ['#fbd3d1', '#f19a97', '#e34948', '#b42f2e', '#7d1d1c']
MID = '#f0efec'
INK, MUTED, GRID, SURFACE = '#0b0b0b', '#52514e', '#d9d8d4', '#fcfcfb'
CRITICAL = '#d03b3b'
LIMIT = 500  # veh/hr/lane at the ends of the color scale
EXCLUDED = ('8208', '8209', '8249', '8251')  # S2045 and S2051, see summarize.py
SUSPECT_FLOW = 30  # veh/hr; a ramp detector averaging less is treated as counting wrongly


def read_stations(path, inventory):
    loc = {r['name']: (float(r['lat']), float(r['lon'])) for r in csv.DictReader(open(inventory, encoding='utf-8-sig'))}
    stations = {}
    for line in open(path):
        m = re.match(r'(\w+) (\d+) cell \d+ \[(.*?)\] .* ([\d.]+) mi', line)
        if m:
            loops = m[3].split('+')
            stations.setdefault(m[1], []).append({'index': int(m[2]), 'loops': loops, 'mi': float(m[4]),
                                                  'latlon': loc[loops[0]]})
    return {d: sorted(s, key=lambda x: x['mi']) for d, s in stations.items()}


def loop_flows(data):
    """average flow (veh/hr) of each loop over its intervals with data"""
    rows = list(csv.reader(open(data)))
    names, kinds = rows[0], rows[1]
    flows = {}
    for j in range(2, len(names)):
        if kinds[j] == 'Volume':
            v = [float(r[j]) for r in rows[3:] if j < len(r) and r[j] not in ('', '-1', '-1.0') and float(r[j]) >= 0]
            flows[names[j].strip()] = sum(v) / len(v) * 120 if v else None
    return flows


def position(latlon, stations):
    """distance (mi) along the polyline through the stations of the point closest to latlon"""
    lat0 = math.radians(stations[0]['latlon'][0])
    xy = lambda p: (p[1] * 69.17 * math.cos(lat0), p[0] * 69.17)  # mi
    px, py = xy(latlon)
    best = (float('inf'), 0)
    for a, b in zip(stations, stations[1:]):
        (ax, ay), (bx, by) = xy(a['latlon']), xy(b['latlon'])
        dx, dy = bx - ax, by - ay
        t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        d = math.hypot(ax + t * dx - px, ay + t * dy - py)
        # beyond the end stations, extrapolate along the first or last segment
        if a is stations[0] or b is stations[-1]:
            t_free = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
            if (a is stations[0] and t_free < 0) or (b is stations[-1] and t_free > 1):
                t = t_free
                d = math.hypot(ax + t * dx - px, ay + t * dy - py)
        if d < best[0]:
            best = (d, a['mi'] + t * (b['mi'] - a['mi']))
    return best[1]


def read_ramps(path, inventory, flows, stations):
    types = {r['name']: r['lane_type'] for r in csv.DictReader(open(inventory, encoding='utf-8-sig'))}
    ramps = {}
    for r in csv.DictReader(open(path)):
        loops = r['loops'].split()
        q = None
        status = 'unmeasured'
        reason = ''
        if loops:
            values = [flows.get(l) for l in loops]
            if any(v is None for v in values):
                status = 'no data'
            else:
                q = sum(values)
                bypass_only = r['type'] == 'Merge' and all(types.get(l) == 'Bypass' for l in loops)
                status = 'suspect' if q < SUSPECT_FLOW or bypass_only else 'measured'
                reason = 'bypass loop only' if bypass_only else f'counts {q:.0f}/hr' if q < SUSPECT_FLOW else ''
        d = r['direction']
        if d in stations:
            ramps.setdefault(d, []).append({'type': r['type'], 'status': status, 'flow': q, 'reason': reason,
                                            'mi': position((float(r['lat']), float(r['lon'])), stations[d])})
    return ramps


def station_flow(st, flows):
    values = [flows.get(l) for l in st['loops']]
    return None if any(v is None for v in values) else sum(values)


def imbalances(stations, ramps, flows):
    """(from mi, to mi, imbalance per lane of the downstream station) for each segment between stations with data"""
    out = []
    for a, b in zip(stations, stations[1:]):
        qa, qb = station_flow(a, flows), station_flow(b, flows)
        if qa is None or qb is None or any(l in EXCLUDED for l in a['loops'] + b['loops']):
            continue
        net = sum((r['flow'] if r['type'] == 'Merge' else -r['flow'])
                  for r in ramps if a['mi'] < r['mi'] <= b['mi'] and r['status'] in ('measured', 'suspect'))
        out.append((a['mi'], b['mi'], (qb - qa - net) / len(b['loops'])))
    return out


def station_errors(folder, direction):
    """{station index: {5-minute block: mean error}} and the mean error of each station"""
    blocks, means = {}, {}
    for path in glob.glob(os.path.join(folder, f'{direction}_*.csv')):
        rows = [r for r in csv.DictReader(open(path)) if r['EKF q'] not in ('', 'NaN') and r['measured q'] != ''
                and not any(l in r['detector'] for l in EXCLUDED)]
        if not rows:
            continue
        s = int(rows[0]['station'])
        b = {}
        for r in rows:
            b.setdefault(int(float(r['time']) // 300), []).append(float(r['EKF q']) - float(r['measured q']))
        blocks[s] = {k: sum(v) / len(v) for k, v in b.items() if len(v) >= 5}
        means[s] = sum(float(r['EKF q']) - float(r['measured q']) for r in rows) / len(rows)
    return blocks, means


def main():
    p = argparse.ArgumentParser()
    p.add_argument('loo')
    p.add_argument('data')
    p.add_argument('stations')
    p.add_argument('output')
    p.add_argument('--inventory', default='data/MN610/MN610_Edited.csv')
    p.add_argument('--ramps', default='data/MN610/ramps.csv')
    p.add_argument('--start', default='15:00')
    p.add_argument('--title', default='')
    a = p.parse_args()

    hour, minute = (int(x) for x in a.start.split(':'))
    t0 = hour + minute / 60
    flows = loop_flows(a.data)
    stations = read_stations(a.stations, a.inventory)
    ramps = read_ramps(a.ramps, a.inventory, flows, stations)
    cmap = LinearSegmentedColormap.from_list('div', BLUE + [MID] + RED)
    directions = sorted(stations)

    fig = plt.figure(figsize=(12, 5.2 * len(directions)), dpi=150, facecolor=SURFACE)
    grid = fig.add_gridspec(len(directions), 3, width_ratios=[3.2, 1.25, 0.08], wspace=0.06, hspace=0.32)
    for row, d in enumerate(directions):
        st = stations[d]
        blocks, means = station_errors(a.loo, d)
        length = st[-1]['mi'] + 0.5
        strip = 0.022 * length
        ax = fig.add_subplot(grid[row, 0], facecolor=SURFACE)
        side = fig.add_subplot(grid[row, 1], sharey=ax, facecolor=SURFACE)
        times = sorted({k for b in blocks.values() for k in b})
        x0, x1 = t0 + times[0] / 12, t0 + (times[-1] + 1) / 12
        for s in st:
            excluded = any(l in EXCLUDED for l in s['loops'])
            y = s['mi']
            if excluded or s['index'] not in blocks:
                ax.add_patch(Rectangle((x0, y - strip / 2), x1 - x0, strip, facecolor='none', edgecolor=GRID,
                                       hatch='////', linewidth=0))
                continue
            for k, e in blocks[s['index']].items():
                ax.add_patch(Rectangle((t0 + k / 12, y - strip / 2), 1 / 12, strip,
                                       facecolor=cmap(0.5 + max(-1, min(1, e / LIMIT)) / 2), edgecolor='none'))
        ax.set_xlim(x0, x1)
        ax.set_ylim(-0.3, length)
        ticks = [h / 2 for h in range(math.ceil(x0 * 2), math.floor(x1 * 2) + 1)]
        ax.set_xticks(ticks)
        ax.set_xticklabels([f'{int(h) % 12 or 12}:{int(60 * (h % 1)):02d}' for h in ticks])
        ax.set_ylabel(f'{d}: distance in the direction of travel (mi)', color=INK)
        ax.set_title(f'{d}: estimated minus measured flow at each station while it was withheld (5-minute averages)',
                     loc='left', fontsize=10, color=INK)
        if row == len(directions) - 1:
            ax.set_xlabel('Time', color=INK)

        # right: segment imbalance (bars), station mean error (dots), ramps (markers at the left edge)
        for lo, hi, imb in imbalances(st, ramps.get(d, []), flows):
            side.add_patch(Rectangle((0, lo + 0.02), imb, hi - lo - 0.04, facecolor='#c3c2b7', edgecolor='none'))
        side.axvline(0, color=MUTED, linewidth=0.8)
        for s in st:
            if s['index'] in means and not any(l in EXCLUDED for l in s['loops']):
                e = means[s['index']]
                side.plot(e, s['mi'], 'o', markersize=5, color=cmap(0.5 + max(-1, min(1, e / LIMIT)) / 2),
                          markeredgecolor=INK, markeredgewidth=0.6, zorder=3)
        xr = 1000
        side.set_xlim(-xr, xr)
        side.set_xticks([-500, 0, 500])
        ramp_x = -xr * 0.9
        for r in ramps.get(d, []):
            marker = '>' if r['type'] == 'Merge' else '<'
            if r['status'] == 'measured':
                side.plot(ramp_x, r['mi'], marker, color=INK, markersize=5, zorder=3)
            elif r['status'] == 'suspect':
                side.plot(ramp_x, r['mi'], 'X', color=CRITICAL, markersize=7, markeredgecolor=SURFACE,
                          markeredgewidth=0.5, zorder=3)
                side.annotate(r['reason'], (ramp_x, r['mi']), xytext=(7, -2.5), textcoords='offset points', fontsize=6.5,
                              color=INK)
            else:
                side.plot(ramp_x, r['mi'], marker, color=INK, markerfacecolor=SURFACE, markersize=5, zorder=3)
        side.set_title('Imbalance (bars) and mean error (dots)', loc='left', fontsize=10, color=INK)
        if row == len(directions) - 1:
            side.set_xlabel('veh/hr/lane', color=INK)
        plt.setp(side.get_yticklabels(), visible=False)
        for axis in (ax, side):
            axis.grid(axis='y', color=GRID, linewidth=0.5)
            axis.set_axisbelow(True)
            for spine in axis.spines.values():
                spine.set_color(GRID)
            axis.tick_params(colors=MUTED)

        cax = fig.add_subplot(grid[row, 2])
        bar = fig.colorbar(plt.cm.ScalarMappable(norm=plt.Normalize(-LIMIT, LIMIT), cmap=cmap), cax=cax)
        bar.set_label('Flow error (veh/hr/lane)', color=INK)
        bar.outline.set_edgecolor(GRID)
        bar.ax.tick_params(colors=MUTED)

    handles = [Line2D([], [], marker='>', linestyle='', color=INK, label='entrance, measured'),
               Line2D([], [], marker='<', linestyle='', color=INK, label='exit, measured'),
               Line2D([], [], marker='>', linestyle='', color=INK, markerfacecolor=SURFACE, label='ramp without detector or data'),
               Line2D([], [], marker='X', linestyle='', color=CRITICAL, markersize=8, label='ramp detector counting wrongly'),
               Patch(facecolor='#c3c2b7', label='unexplained flow between stations'),
               Patch(facecolor='none', edgecolor=GRID, hatch='////', label='station excluded (auxiliary lanes) or without data')]
    fig.legend(handles=handles, loc='lower center', ncol=3, frameon=False, fontsize=9, labelcolor=INK,
               bbox_to_anchor=(0.5, 0.0))
    if a.title:
        fig.suptitle(a.title, x=0.125, ha='left', fontsize=12, color=INK)
    fig.subplots_adjust(left=0.07, right=0.94, top=0.93, bottom=0.11)
    fig.savefig(a.output, facecolor=SURFACE)


if __name__ == '__main__':
    main()
