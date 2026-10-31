"""Free flow speed of each mainline detector station, from historical detector data (HCM field method).

The free flow speed of a station is the median of its 30-second speeds when the flow is below 1000 veh/hr/ln. Station
speeds are the count-weighted average of the speeds of its loops, and intervals with missing data at any loop are skipped.
The median, rather than the HCM's average, is used because at stations inside recurring queues (e.g. EB 8209-8219), some
low-flow intervals are congested.

Stations are the mainline loops of the detector inventory, grouped by MnDOT station ID like ReadData.readDetectors():
loops of type Mainline or Auxiliary, except the auxiliary loops modeled as exits (ReadData.AUXILIARY_EXITS).

Usage (from the PW_estimation folder):
  python3 tools/calibration/free_flow_speed.py <inventory CSV> <output CSV> <detector data CSV> [detector data CSV ...]
e.g. python3 tools/calibration/free_flow_speed.py data/MN610/MN610_Edited.csv data/MN610/free_flow_speed.csv \\
       $(ls data/MN610/daily/*.csv | grep -v -e 09-02 -e 09-17)
Do not include the days used for validation. Read by ReadData.readFreeFlowSpeeds().
"""
import csv
import statistics
import sys

MAX_FLOW = 1000  # veh/hr/ln
AUXILIARY_EXITS = {'8249', '8250'}  # same as ReadData.AUXILIARY_EXITS


def read_stations(path):
    """mainline stations: (direction label, station ID) -> {'loops': [...], 'lat': ..., 'lon': ...}"""
    stations = {}
    with open(path, encoding='utf-8-sig') as f:
        for row in csv.reader(f):
            if len(row) < 17 or row[11] not in ('Mainline', 'Auxiliary') or row[2] in AUXILIARY_EXITS:
                continue
            key = (row[9].split('/')[0], row[3].strip())
            if not key[1]:
                continue
            s = stations.setdefault(key, {'loops': [], 'lat': float(row[15]), 'lon': float(row[16])})
            if row[2] not in s['loops']:
                s['loops'].append(row[2])
    return stations


def main():
    inventory, output, data_files = sys.argv[1], sys.argv[2], sys.argv[3:]
    stations = read_stations(inventory)
    speeds = {key: [] for key in stations}

    for path in data_files:
        with open(path) as f:
            rows = list(csv.reader(f))
        # row 0: loop ID of each column, row 1: Speed or Volume, row 2: date, then one row per 30 seconds
        columns = {}
        for j in range(2, len(rows[0])):
            columns.setdefault(rows[0][j], {})[rows[1][j]] = j
        for key, s in stations.items():
            if not all(loop in columns for loop in s['loops']):
                continue
            for r in rows[3:]:
                count = 0
                weighted = 0
                valid = True
                for loop in s['loops']:
                    volume = float(r[columns[loop]['Volume']])
                    speed = float(r[columns[loop]['Speed']])
                    if volume < 0:
                        valid = False
                        break
                    if volume > 0 and speed > 0:
                        count += volume
                        weighted += speed * volume
                if valid and count > 0 and count * 120 / len(s['loops']) < MAX_FLOW:
                    speeds[key].append(weighted / count)

    with open(output, 'w', newline='') as f:
        out = csv.writer(f)
        out.writerow(['road', 'station', 'loops', 'lat', 'lon', 'free_flow_speed', 'samples'])
        for key in sorted(stations):
            s = stations[key]
            if speeds[key]:
                ffs = statistics.median(speeds[key])
                out.writerow([key[0], key[1], ' '.join(s['loops']), s['lat'], s['lon'], f'{ffs:.1f}', len(speeds[key])])
                print(f'{key[0]} {key[1]:6} {"+".join(s["loops"]):22} {ffs:5.1f} mi/hr ({len(speeds[key])} samples)')


if __name__ == '__main__':
    main()
