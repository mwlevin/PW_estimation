"""Plots measured and estimated speed and density at withheld stations from the leave-one-out validation files.

Usage: python3 plot_stations.py <validation folder> <output PDF> <clock time of t = 0, HH:MM> <EB_n:label> [<WB_n:label> ...]
For example: python3 plot_stations.py out fig.pdf 15:00 "EB_11:Eastbound, W Broadway Ave" "WB_5:Westbound, T.H. 252"
Values are 2-minute averages of the 30-second measurements and estimates.
"""
import csv
import os
import sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

AVERAGE = 4  # 30-second intervals per plotted point


def load(path):
    rows = list(csv.DictReader(open(path)))
    out = {k: [] for k in ('time', 'measured u', 'measured k', 'EKF u', 'EKF k', 'interpolated u', 'interpolated k')}
    for i in range(0, len(rows) - AVERAGE + 1, AVERAGE):
        group = rows[i:i + AVERAGE]
        out['time'].append(float(group[0]['time']) + 15 * AVERAGE)
        for k in out:
            if k != 'time':
                values = [float(r[k]) for r in group if r[k] != '']
                out[k].append(sum(values) / len(values) if values else float('nan'))
    return out


def main():
    folder, output, start = sys.argv[1], sys.argv[2], sys.argv[3]
    h, m = (int(x) for x in start.split(':'))
    stations = [s.split(':', 1) for s in sys.argv[4:]]
    fig, axes = plt.subplots(2, len(stations), figsize=(6.5 * len(stations) / 2 + 0.5, 5), sharex=True, squeeze=False)
    for col, (name, label) in enumerate(stations):
        d = load(os.path.join(folder, name + '.csv'))
        hours = [h + m / 60 + t / 3600 for t in d['time']]
        for row, (var, unit) in enumerate((('u', 'Speed (mi/hr)'), ('k', 'Density (veh/mi/lane)'))):
            ax = axes[row][col]
            ax.plot(hours, d['measured ' + var], color='black', lw=1.6, label='Measured (withheld)')
            ax.plot(hours, d['EKF ' + var], color='#d62728', lw=1.3, label='Extended Kalman filter')
            ax.plot(hours, d['interpolated ' + var], color='#1f77b4', lw=1.0, ls='--', label='Interpolation')
            ax.set_ylim(bottom=0)
            ax.grid(alpha=0.3)
            if col == 0:
                ax.set_ylabel(unit)
            if row == 0:
                ax.set_title(label, fontsize=11)
            if row == 1:
                ticks = range(h, h + 4)
                ax.set_xticks(list(ticks))
                ax.set_xticklabels([f'{(t - 1) % 12 + 1}:00 PM' if t >= 12 else f'{t}:00 AM' for t in ticks])
    axes[0][0].legend(fontsize=8, loc='lower left')
    fig.tight_layout()
    fig.savefig(output)


if __name__ == '__main__':
    main()
