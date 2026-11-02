"""Finds days with congestion in both peaks, to choose test days for a corridor.

Usage: python3 tools/corridors/find_congestion.py <corridor folder>
Reads <folder>/inventory.csv (made by build_corridor.py) and the day files in <folder>/daily (made by extract.sh). The
speed of a station is the average of its loops' speeds, and a 30-second interval is congested if it is below 45 mi/hr.
A day qualifies if some station is congested for at least MIN_MINUTES between 7 and 9 AM, and some station (in either
direction) for at least MIN_MINUTES between 4 and 6 PM.
"""
import csv
import glob
import os
import sys

CONGESTED = 45  # mi/hr
MIN_MINUTES = 30
PEAKS = {'AM': (7, 9), 'PM': (16, 18)}


def seconds(clock):
    """seconds after midnight of a time such as 3:00:30 PM, 6 AM or Noon, as written by DataExtract"""
    clock = {'Noon': '12:00 PM', 'Midnight': '12:00 AM'}.get(clock.strip(), clock.strip())
    if ':' not in clock:
        clock = clock.replace(' ', ':00 ')
    parts = clock.replace(' ', ':').split(':')
    hour = int(parts[0])
    if parts[-1] in ('AM', 'PM'):
        hour = hour % 12 + (12 if parts[-1] == 'PM' else 0)
        parts = parts[:-1]
    return hour * 3600 + int(parts[1]) * 60 + (int(parts[2]) if len(parts) > 2 else 0)


def day_summary(path, stations):
    rows = list(csv.reader(open(path)))
    if len(rows) < 4 or rows[1][2] != 'Speed':
        return None  # still being written by extract.sh (detectors as rows until it finishes)
    loops, kinds = rows[0], rows[1]
    congested = {}  # station -> {period: congested intervals}
    for row in rows[3:]:
        hour = (seconds(row[1]) - 1) / 3600
        speeds = {}
        for j in range(2, min(len(row), len(loops))):
            if kinds[j] == 'Speed' and loops[j] in stations and row[j] not in ('', '-1.0', '-1') and float(row[j]) > 0:
                speeds.setdefault(stations[loops[j]], []).append(float(row[j]))
        for s, v in speeds.items():
            slow = sum(v) / len(v) < CONGESTED
            for period, (start, end) in PEAKS.items():
                if start <= hour < end and slow:
                    congested.setdefault(s, {}).setdefault(period, 0)
                    congested[s][period] += 1
    # longest congestion (minutes) at any station in each period, and where; and the total over all stations
    best, total = {}, {}
    for period in PEAKS:
        candidates = [(c.get(period, 0) / 2, s) for s, c in congested.items()]
        best[period] = max(candidates) if candidates else (0, None)
        total[period] = sum(c.get(period, 0) / 2 for c in congested.values())
    return best, total


def main(folder):
    stations = {}  # loop -> (direction, station ID, location)
    for r in csv.DictReader(open(os.path.join(folder, 'inventory.csv'), encoding='utf-8-sig')):
        if r['lane_type'] in ('Mainline', 'Auxiliary', 'HOT', 'HOV'):
            stations[r['name']] = (r['location'].split('|')[0], r['station'] or r['location'], r['location'].split('/')[-1])
    qualifying = []
    for path in sorted(glob.glob(os.path.join(folder, 'daily', '*.csv'))):
        result = day_summary(path, stations)
        if result is None:
            continue
        best, total = result
        day = os.path.basename(path)[:-4]
        ok = all(best[p][0] >= MIN_MINUTES for p in PEAKS)
        print(f"{day}: " + '; '.join(f"{p} {best[p][0]:.0f} min at {' '.join(best[p][1]) if best[p][1] else '-'}"
                                     f" ({total[p]:.0f} station-min in all)" for p in PEAKS)
              + (' QUALIFIES' if ok else ''))
        if ok:
            qualifying.append((min(total[p] for p in PEAKS), day))
    if qualifying:
        print(f'Most congested qualifying day (congested station-minutes in the weaker peak): {max(qualifying)[1]}')


if __name__ == '__main__':
    main(sys.argv[1])
