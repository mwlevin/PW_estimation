"""Space-time heatmap of the estimated density (or speed), with the detector measurements drawn at their stations.

Usage: python3 tools/validation/heatmap.py <cells CSV> <stations CSV> <output PNG> [options]
  <cells CSV>     written by Validation with -Dcells_out=<file>: the estimate in every cell, every 30 seconds
  <stations CSV>  the output CSV of the same Validation run (station "none" for all stations, or a station number):
                  the measurements of the stations, and of the withheld station, which is outlined with a dashed line
Options:
  --value density|speed   (default density)
  --start HH:MM           clock time of time 0 of the data file, for the time axis (default 15:00)
  --title "..."           (default: the file name)
  --max N                 top of the color scale (default 100 veh/mi/lane, or 75 mi/hr for speed)

Time runs left to right and distance (in the direction of travel) bottom to top, so a queue that grows backward is a
dark band that widens toward the bottom right. Each station's measurements are drawn as a thin strip at its position in
the same colors, with a dark outline; where the strip and the estimate around it have the same color, the estimate
matches the detector. Gaps in a strip are missing data.
"""
import argparse
import csv
import math

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.patches import Rectangle

# sequential blue ramp, light (low) to dark (high)
BLUES = ['#f4f8fd', '#cde2fb', '#9ec5f4', '#6da7ec', '#3987e5', '#256abf', '#184f95', '#0d366b']
INK, MUTED = '#222222', '#6b6b6b'
STRIP = 0.025  # height of a station's strip, as a fraction of the corridor length


def main():
    p = argparse.ArgumentParser()
    p.add_argument('cells')
    p.add_argument('stations')
    p.add_argument('output')
    p.add_argument('--value', default='density', choices=('density', 'speed'))
    p.add_argument('--start', default='15:00')
    p.add_argument('--title')
    p.add_argument('--max', type=float)
    a = p.parse_args()

    column = 'EKF k' if a.value == 'density' else 'EKF u'
    measured = 'measured k' if a.value == 'density' else 'measured u'
    vmax = a.max or (100 if a.value == 'density' else 75)
    cmap = LinearSegmentedColormap.from_list('ramp', BLUES if a.value == 'density' else BLUES[::-1])
    hour, minute = (int(x) for x in a.start.split(':'))
    t0 = hour + minute / 60  # hours

    cells = {}
    times = set()
    for r in csv.DictReader(open(a.cells)):
        c = int(r['cell'])
        cells.setdefault(c, {'position': float(r['position (mi)']), 'values': {}})
        t = int(r['time'])
        cells[c]['values'][t] = float(r[column])
        times.add(t)
    times = sorted(times)
    order = sorted(cells)
    dt = (times[1] - times[0]) if len(times) > 1 else 30
    positions = [cells[c]['position'] for c in order]
    cell_length = positions[1] - positions[0] if len(positions) > 1 else 0.1
    # cell edges in space and interval edges in time
    y_edges = [x - cell_length / 2 for x in positions] + [positions[-1] + cell_length / 2]
    x_edges = [t0 + t / 3600 for t in times] + [t0 + (times[-1] + dt) / 3600]
    grid = [[cells[c]['values'].get(t, math.nan) for t in times] for c in order]

    stations = {}
    withheld = None
    for r in csv.DictReader(open(a.stations)):
        c = int(r['cell'])
        s = stations.setdefault(c, {'detector': r['detector'], 'values': {}})
        s['values'][int(float(r['time']))] = float(r[measured])
    # with a station withheld, its file has only that station
    if len(stations) == 1:
        withheld = next(iter(stations))

    length = y_edges[-1] - y_edges[0]
    fig, ax = plt.subplots(figsize=(11, 6.5), dpi=150)
    mesh = ax.pcolormesh(x_edges, y_edges, grid, cmap=cmap, vmin=0, vmax=vmax, shading='flat')
    strip = STRIP * length
    for c, s in sorted(stations.items()):
        y = cells[c]['position']
        for t, v in s['values'].items():
            x = t0 + t / 3600
            ax.add_patch(Rectangle((x, y - strip / 2), 30 / 3600, strip, facecolor=cmap(min(v, vmax) / vmax), edgecolor='none'))
        # a white edge separates the measurements from the estimate around them; the withheld station's is dashed and dark
        ax.add_patch(Rectangle((x_edges[0], y - strip / 2), x_edges[-1] - x_edges[0], strip, fill=False,
                               edgecolor=INK if c == withheld else 'white', linewidth=1 if c == withheld else 0.8,
                               linestyle='--' if c == withheld else '-'))

    ax.set_xlim(x_edges[0], x_edges[-1])
    ax.set_ylim(y_edges[0], y_edges[-1])
    ticks = [h for h in range(math.ceil(x_edges[0] * 2), math.floor(x_edges[-1] * 2) + 1)]
    ax.set_xticks([h / 2 for h in ticks])
    ax.set_xticklabels([f'{int(h / 2) % 12 or 12}:{30 * (h % 2):02d}' for h in ticks])
    ax.set_xlabel('Time', color=INK)
    ax.set_ylabel('Distance in the direction of travel (mi)', color=INK)
    # the color bar first, so that the right axis has the same (narrowed) position as the plot
    bar = fig.colorbar(mesh, ax=ax, pad=0.1, fraction=0.04)
    bar.set_label('Density (veh/mi/lane)' if a.value == 'density' else 'Speed (mi/hr)', color=INK)
    bar.outline.set_edgecolor(MUTED)
    bar.ax.tick_params(colors=MUTED)
    right = ax.twinx()
    right.set_ylim(ax.get_ylim())
    right.set_yticks([cells[c]['position'] for c in sorted(stations)])
    right.set_yticklabels([stations[c]['detector'].strip('[]').split('+')[0] for c in sorted(stations)], fontsize=6, color=MUTED)
    right.set_ylabel('Detector station (first loop)', color=MUTED)
    for axis in (ax, right):
        for side in axis.spines.values():
            side.set_color(MUTED)
        axis.tick_params(colors=MUTED)
    note = 'Strips: detector measurements' + ('; dashed: withheld from the filter' if withheld is not None else '')
    ax.set_title((a.title or a.output) + '\n', color=INK, fontsize=11, loc='left')
    ax.text(0, 1.01, note, transform=ax.transAxes, fontsize=8, color=MUTED)
    fig.savefig(a.output)


if __name__ == '__main__':
    main()
