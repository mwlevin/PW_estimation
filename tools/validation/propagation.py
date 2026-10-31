"""Backward propagation of queues: when congestion reaches each detector station, in the data and in the estimates.

A queue that forms at a bottleneck grows backward, so it reaches the stations upstream one after another. For each pair of
adjacent stations where the downstream station became congested first and the upstream one within MAX_GAP afterwards, the
time between the two onsets is the propagation time over the distance between them. In the leave-one-out files written by
validate.sh, the upstream station of the pair is withheld, so the estimate's onset there comes from how the model carries
the queue backward from the downstream station, which the filter does see. Comparing the estimated with the measured
propagation times shows whether the model's queues grow too fast or too slow.

Usage: python3 tools/validation/propagation.py <leave-one-out folder> <station list>
The station list is the output of "Validation <data> 60 EB list" followed by the same for WB, each line prefixed with its
direction (e.g. "EB 3 cell 16 [7798+7799] (2 loops, 2.0 lanes) 2.123 mi").
"""
import csv
import glob
import os
import re
import statistics
import sys

from summarize import EXCLUDED, CONGESTED

SMOOTH = 5  # 30-second intervals in the rolling median that removes single slow or fast intervals
BEFORE = 6  # intervals (3 min) that must be uncongested before an onset
AFTER = 10  # intervals (5 min) after an onset, at least 80% of which must be congested
MAX_GAP = 30 * 60  # s; longest time between the onsets at the downstream and upstream stations of a pair
MATCH = 20 * 60  # s; an estimated onset counts for a measured one if it is this close


def smoothed(speeds):
    output = []
    for i in range(len(speeds)):
        window = [u for u in speeds[max(0, i - SMOOTH // 2):i + SMOOTH // 2 + 1] if u is not None]
        output.append(statistics.median(window) if window else None)
    return output


def stays_congested(smooth, i):
    """true if the smoothed speed is below CONGESTED at interval i and in at least 80% of the AFTER intervals from it"""
    after = [u for u in smooth[i:i + AFTER] if u is not None]
    return smooth[i] is not None and smooth[i] < CONGESTED and len(after) >= AFTER // 2 \
        and sum(u < CONGESTED for u in after) >= 0.8 * len(after)


def onsets(times, speeds):
    """times (s) at which the smoothed speed falls below CONGESTED and stays there, after BEFORE uncongested intervals"""
    smooth = smoothed(speeds)
    output = []
    for i in range(BEFORE, len(smooth) - AFTER):
        before = [u for u in smooth[i - BEFORE:i] if u is not None]
        if before and all(u >= CONGESTED for u in before) and stays_congested(smooth, i):
            output.append(times[i])
    return output


def arrival(times, speeds, start, end):
    """first time in [start, end] at which the smoothed estimate falls below CONGESTED and stays there; 'already' if it is
    already congested at start, so the estimate's queue was there before the downstream station's (e.g. a smoothed-out
    stop-and-go wave inside a queue), or None if it never arrives"""
    smooth = smoothed(speeds)
    for i, t in enumerate(times):
        if start <= t <= end and stays_congested(smooth, i):
            return 'already' if i > 0 and times[i - 1] < start or t == start else t
    return None


def load(folder, station_list):
    positions = {}  # (direction, station number) -> (mi, detector)
    for line in open(station_list):
        m = re.match(r'(\w+) (\d+) cell \d+ (\S+) .* ([\d.]+) mi', line)
        if m:
            positions[(m.group(1), int(m.group(2)))] = (float(m.group(4)), m.group(3))
    series = {}  # (direction, station number) -> {'time': [...], 'measured u': [...], ...}
    for path in glob.glob(os.path.join(folder, '[EW]B_*.csv')):
        direction, number = os.path.basename(path)[:-4].split('_')
        data = {'time': [], 'measured u': [], 'EKF u': [], 'interpolated u': []}
        for r in csv.DictReader(open(path)):
            data['time'].append(float(r['time']))
            for k in ('measured u', 'EKF u', 'interpolated u'):
                data[k].append(float(r[k]) if r[k] != '' and float(r[k]) >= 0 else None)
        series[(direction, int(number))] = data
    return positions, series


def main(folder, station_list):
    positions, series = load(folder, station_list)
    events = []
    for (direction, s), (position, detector) in sorted(positions.items()):
        down = (direction, s + 1)
        if down not in positions or (direction, s) not in series or down not in series \
                or any(d in detector or d in positions[down][1] for d in EXCLUDED):
            continue
        data = series[(direction, s)]
        if all(u is None for u in data['interpolated u']):
            continue  # first or last station: not estimated by leave-one-out
        distance = positions[down][0] - position
        down_onsets = onsets(series[down]['time'], series[down]['measured u'])
        for t in onsets(data['time'], data['measured u']):
            earlier = [t_d for t_d in down_onsets if 0 < t - t_d <= MAX_GAP]
            if not earlier:
                continue
            t_down = max(earlier)
            event = {'direction': direction, 'station': s, 'detector': detector, 'distance': distance, 'down': t_down,
                     'measured': t}
            for k in ('EKF u', 'interpolated u'):
                event[k] = arrival(data['time'], data[k], t_down - 5 * 60, t + MATCH)
            events.append(event)

    print(f"{len(events)} queue arrivals at withheld stations, after the downstream station (within {MAX_GAP // 60} min)")
    print(f"  {'':5} {'station':>7} {'detector':>22} {'mi':>5} {'onset':>6} | {'propagation time (min)':>22}")
    print(f"  {'':5} {'':>7} {'':>22} {'':>5} {'':>6} | {'measured':>8} {'EKF':>6} {'interp.':>7}")
    for e in events:
        cells = []
        for k, w in (('measured', 8), ('EKF u', 6), ('interpolated u', 7)):
            cells.append(f"{(e[k] - e['down']) / 60:{w}.1f}" if isinstance(e[k], float) else f"{e[k] or 'miss':>{w}}")
        print(f"  {e['direction']:5} {e['station']:7} {e['detector']:>22} {e['distance']:5.2f} "
              f"{int(15 + e['measured'] // 3600):2d}:{int(e['measured'] % 3600 // 60):02d} | " + ' '.join(cells))

    measured_time = sum(e['measured'] - e['down'] for e in events)
    distance = sum(e['distance'] for e in events)
    print(f"Measured backward propagation speed: {distance / measured_time * 3600:.1f} mi/hr (total distance / total time)")
    for k, name in (('EKF u', 'EKF'), ('interpolated u', 'Interpolation')):
        found = [e for e in events if isinstance(e[k], float)]
        already = sum(e[k] == 'already' for e in events)
        missed = sum(e[k] is None for e in events)
        if not found:
            print(f"{name}: no onsets found")
            continue
        errors = [(e[k] - e['measured']) / 60 for e in found]
        m_time = sum(e['measured'] - e['down'] for e in found)
        e_time = sum(e[k] - e['down'] for e in found)
        dist = sum(e['distance'] for e in found)
        speed = f"{dist / e_time * 3600:.1f}" if e_time > 0 else "--"
        print(f"{name}: arrives {len(found)} of {len(events)}, already congested {already}, never {missed}; onset error (estimated - measured): mean {statistics.mean(errors):+.1f} min, "
              f"median {statistics.median(errors):+.1f}, mean absolute {statistics.mean(abs(x) for x in errors):.1f}; "
              f"propagation speed {speed} mi/hr vs measured {dist / m_time * 3600:.1f} on the same arrivals")


if __name__ == '__main__':
    sys.path.insert(0, os.path.dirname(__file__))
    main(sys.argv[1], sys.argv[2])
