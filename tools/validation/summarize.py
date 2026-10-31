"""Summarizes the leave-one-out validation files written by validate.sh: root-mean-square errors at withheld stations."""
import csv
import glob
import math
import os
import sys

CONGESTED = 45  # mi/hr


# number of consecutive 30-second intervals averaged before comparing (environment variable AGG; 2 = 1 minute)
AGG = int(os.environ.get('AGG', 1))
VALUES = ('measured u', 'measured k', 'EKF u', 'EKF k', 'interpolated u', 'interpolated k')
# flow (veh/hr/lane), in files written after flow was added to Validation; unlike speed and density, it does not depend on
# the vehicle length that loop detectors assume
FLOW_VALUES = ('measured q', 'EKF q', 'interpolated q')


# stations left out of all comparisons: S2045 (EB W Broadway Ave) and S2051 (WB West River Rd) have auxiliary-lane loops
# with fast, exiting traffic next to a queue in the mainline lanes, so their average over all loops does not represent the
# mainline lanes the model estimates. S2051's auxiliary-lane loops (8249, 8250) are modeled as an exit
# (ReadData.AUXILIARY_EXITS), so its station is its mainline loops 8251 and 8252; 8209 covers S2045 if 8208 is too.
EXCLUDED = ('8208', '8209', '8249', '8251')


def load(folder):
    rows = []
    for path in sorted(glob.glob(os.path.join(folder, '[EW]B_*.csv'))):
        station = []
        for r in csv.DictReader(open(path)):
            if r['interpolated u'] == '' or any(d in r['detector'] for d in EXCLUDED):
                continue
            station.append({k: (v if k in ('direction', 'detector') else float(v) if v != '' else None) for k, v in r.items()})
        # average blocks of AGG intervals that start at multiples of AGG * 30 s and have all their intervals
        blocks = {}
        for r in station:
            blocks.setdefault(int(r['time'] // (30 * AGG)), []).append(r)
        for block in blocks.values():
            if len(block) == AGG:
                avg = dict(block[0])
                for k in VALUES:
                    avg[k] = sum(r[k] for r in block) / AGG
                for k in FLOW_VALUES:
                    avg[k] = sum(r[k] for r in block) / AGG if all(r.get(k) is not None for r in block) else None
                rows.append(avg)
    return rows


def rmse(errors):
    return math.sqrt(sum(e * e for e in errors) / len(errors)) if errors else float('nan')


def table(rows, label):
    cong = [r for r in rows if r['measured u'] < CONGESTED]
    print(f"{label}: {len(rows)} measurements, {len(cong)} congested (< {CONGESTED} mi/hr), "
          f"mean measured speed {sum(r['measured u'] for r in rows) / len(rows):.1f} mi/hr")
    print(f"  {'':16} {'speed':>7} {'density':>8} | congested: {'speed':>6} {'density':>8}")
    for name, u, k in (('EKF', 'EKF u', 'EKF k'), ('Interpolation', 'interpolated u', 'interpolated k')):
        print(f"  {name:16} {rmse([r[u] - r['measured u'] for r in rows]):7.1f} {rmse([r[k] - r['measured k'] for r in rows]):8.1f} |"
              f"            {rmse([r[u] - r['measured u'] for r in cong]):6.1f} {rmse([r[k] - r['measured k'] for r in cong]):8.1f}")
    ffs = float(os.environ.get('FFS', 69))
    print(f"  {'Free flow speed':16} {rmse([ffs - r['measured u'] for r in rows]):7.1f} {'--':>8} |"
          f"            {rmse([ffs - r['measured u'] for r in cong]):6.1f} {'--':>8}")
    for name, u in (('EKF', 'EKF u'), ('Interpolation', 'interpolated u')):
        hit = sum(1 for r in cong if r[u] < CONGESTED)
        false = sum(1 for r in rows if r['measured u'] >= CONGESTED and r[u] < CONGESTED)
        print(f"  {name} detects {100 * hit / max(len(cong), 1):.0f}% of congested measurements, "
              f"false alarms {100 * false / max(len(rows) - len(cong), 1):.1f}% of uncongested")
    flow = [r for r in rows if all(r.get(k) is not None for k in FLOW_VALUES)]
    if flow:
        flow_cong = [r for r in flow if r['measured u'] < CONGESTED]
        print(f"  Flow (veh/hr/ln):  EKF {rmse([r['EKF q'] - r['measured q'] for r in flow]):.0f}, "
              f"interpolation {rmse([r['interpolated q'] - r['measured q'] for r in flow]):.0f} | congested: "
              f"EKF {rmse([r['EKF q'] - r['measured q'] for r in flow_cong]):.0f}, "
              f"interpolation {rmse([r['interpolated q'] - r['measured q'] for r in flow_cong]):.0f}")


if __name__ == '__main__':
    rows = load(sys.argv[1])
    ends = {}
    for r in rows:
        ends.setdefault(r['direction'], set()).add(int(r['station']))
    first_last = {(d, min(s)) for d, s in ends.items()} | {(d, max(s)) for d, s in ends.items()}
    interior = [r for r in rows if (r['direction'], int(r['station'])) not in first_last]
    table(interior, 'Interior stations, both directions')
    for d in ('EB', 'WB'):
        table([r for r in interior if r['direction'] == d], f'Interior stations, {d}')
    table([r for r in rows if (r['direction'], int(r['station'])) in first_last], 'First and last stations')
