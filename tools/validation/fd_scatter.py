"""Flow-density scatterplot of the detector measurements with the model's fundamental diagram superimposed.

Usage: python3 tools/validation/fd_scatter.py <output PNG> <leave-one-out folder> [<folder> ...] [--title "..."]
  folders  written by validate.sh; the measurements are those of the withheld stations, with the station speed factors
           applied, i.e. the values the estimates are compared with

Left: 30-second measurements; right: the same averaged over 5 minutes, which removes most of the random variation in
the counts. Points are colored by the measured speed (congested below 45 mi/hr). The text gives the root-mean-square
difference between the measured flow and the fundamental diagram at the measured density, for each regime.
"""
import argparse
import math
import os
import sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import summarize

# fundamental diagram of the model (Section 3): free-flow speed (mi/hr), capacity (veh/hr/lane), congested wave speed
# (mi/hr) and where that line reaches zero flow (veh/mi/lane), and the second congested line, to the jam density; the
# congested flow is the larger of the two lines, and the queue discharge rate is the congested flow at the critical density
V, Q, W, K = 69, 2200, 17, 140.9
W2, K2 = 9, 190
CRITICAL = Q / V
BREAK = (W * K - W2 * K2) / (W - W2)  # density where the lines meet
DISCHARGE = W * (K - CRITICAL)
CONGESTED = summarize.CONGESTED
FREE_COLOR, CONGESTED_COLOR = '#2a78d6', '#eb6834'
INK, MUTED, GRID, SURFACE = '#0b0b0b', '#52514e', '#e4e3df', '#fcfcfb'


def equilibrium_flow(k):
    return V * k if k <= CRITICAL else max(0, min(DISCHARGE, max(W * (K - k), W2 * (K2 - k))))


def rms(xs):
    return math.sqrt(sum(x * x for x in xs) / len(xs)) if xs else float('nan')


def load(folders, agg):
    summarize.AGG = agg
    rows = []
    for f in folders:
        rows += [r for r in summarize.load(f) if r.get('measured q') is not None and r['measured k'] and r['measured k'] > 0]
    return rows


def panel(ax, rows, title, size):
    free = [r for r in rows if r['measured u'] >= CONGESTED]
    cong = [r for r in rows if r['measured u'] < CONGESTED]
    for rs, color in ((free, FREE_COLOR), (cong, CONGESTED_COLOR)):
        ax.scatter([r['measured k'] for r in rs], [r['measured q'] for r in rs], s=size, color=color, alpha=0.25,
                   linewidths=0, rasterized=True, zorder=2)
    # fundamental diagram, with the drop from capacity to the discharge rate at the critical density
    ks = [0, CRITICAL]
    ax.plot(ks, [V * k for k in ks], color=INK, linewidth=2, zorder=3, solid_capstyle='round')
    ax.plot([CRITICAL, CRITICAL], [Q, DISCHARGE], color=INK, linewidth=1, linestyle=(0, (2, 2)), zorder=3)
    ax.plot([CRITICAL, BREAK, K2], [DISCHARGE, W * (K - BREAK), 0], color=INK, linewidth=2, zorder=3, solid_capstyle='round',
            solid_joinstyle='round')
    # the 45 mi/hr line that separates the regimes (in the legend)
    ax.plot([0, 2600 / CONGESTED], [0, 2600], color=MUTED, linewidth=0.8, linestyle=(0, (4, 3)), zorder=1)
    d_free = rms([r['measured q'] - equilibrium_flow(r['measured k']) for r in free])
    d_cong = rms([r['measured q'] - equilibrium_flow(r['measured k']) for r in cong])
    ax.text(0.97, 0.97, f'Measured flow minus diagram,\nroot mean square (veh/hr/lane):\nfree flow {d_free:,.0f}\n'
                        f'congested {d_cong:,.0f}', transform=ax.transAxes, ha='right', va='top',
            fontsize=7.5, color=INK, linespacing=1.4)
    ax.set_title(title, loc='left', fontsize=9.5, color=INK)
    ax.set_xlim(0, 200)
    ax.set_ylim(0, 2700)
    ax.set_xlabel('Density (veh/mi/lane)', fontsize=8.5, color=INK)
    ax.grid(color=GRID, linewidth=0.6, zorder=0)
    ax.tick_params(colors=MUTED, labelsize=7.5, length=0)
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda y, _: f'{y:,.0f}'))
    for s in ax.spines.values():
        s.set_visible(False)
    ax.set_facecolor(SURFACE)
    return len(rows), len(cong)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('output')
    p.add_argument('folders', nargs='+')
    p.add_argument('--title', default='')
    a = p.parse_args()

    fig, axes = plt.subplots(1, 2, figsize=(7.5, 3.6), dpi=200, sharey=True, facecolor=SURFACE)
    n30, c30 = panel(axes[0], load(a.folders, 1), '30-second measurements', 3)
    n5, c5 = panel(axes[1], load(a.folders, 10), '5-minute averages', 6)
    axes[0].set_ylabel('Flow (veh/hr/lane)', fontsize=8.5, color=INK)
    # annotations of the diagram, on the left panel
    ax = axes[0]
    ax.annotate(f'capacity {Q:,}', (CRITICAL, Q), (3, Q + 250), fontsize=7, color=INK,
                arrowprops=dict(arrowstyle='-', color=MUTED, linewidth=0.6))
    # on the right panel, where the points leave room
    axes[1].annotate(f'discharge {DISCHARGE:,.0f}', (CRITICAL + 2, DISCHARGE), (62, DISCHARGE + 250), fontsize=7,
                     color=INK, arrowprops=dict(arrowstyle='-', color=MUTED, linewidth=0.6))
    ax.annotate(f'wave speed {W} mi/hr', (70, W * (K - 70)), (95, 1500), fontsize=7, color=INK,
                arrowprops=dict(arrowstyle='-', color=MUTED, linewidth=0.6))
    ax.annotate(f'{W2} mi/hr to jam density {K2}', (150, W2 * (K2 - 150)), (128, 800), fontsize=7, color=INK,
                arrowprops=dict(arrowstyle='-', color=MUTED, linewidth=0.6))
    fig.legend(handles=[Line2D([], [], marker='o', linestyle='', color=FREE_COLOR, markersize=5, label=f'free flow (≥ {CONGESTED} mi/hr)'),
                        Line2D([], [], marker='o', linestyle='', color=CONGESTED_COLOR, markersize=5, label=f'congested (< {CONGESTED} mi/hr)'),
                        Line2D([], [], color=INK, linewidth=2, label='fundamental diagram of the model'),
                        Line2D([], [], color=MUTED, linewidth=0.8, linestyle=(0, (4, 3)), label=f'{CONGESTED} mi/hr')],
               loc='lower center', ncol=4, frameon=False, fontsize=7.5, bbox_to_anchor=(0.5, -0.02), labelcolor=INK)
    if a.title:
        fig.suptitle(a.title, x=0.01, ha='left', fontsize=10.5, color=INK)
    fig.tight_layout(rect=(0, 0.06, 1, 0.97 if a.title else 1))
    fig.savefig(a.output, facecolor=SURFACE, bbox_inches='tight')
    print(f'{a.output}: {n30} 30-second measurements ({c30} congested), {n5} 5-minute averages ({c5} congested)')


if __name__ == '__main__':
    main()
