"""Finds the entrance and exit ramps of a corridor in OpenStreetMap and assigns the ramp detectors to them.

Usage: python3 make_ramps.py <corridor geometry (Overpass JSON)> <ramp ways (Overpass JSON)> <detector inventory CSV> <output CSV>

The corridor geometry is the file read by ReadData.readOSM() (e.g. data/MN610/geometry.txt). The ramp ways are the
highway=motorway_link ways near the corridor, from an Overpass query such as
    [out:json];way["highway"="motorway_link"](45.110,-93.510,45.165,-93.250);out geom tags;
A ramp joins the corridor where one of its ends is on the corridor: an entrance ramp ends there (merge point), and an
exit ramp starts there (diverge point). Each ramp is followed away from the corridor through connected motorway_link ways,
for up to MAX_RAMP_LENGTH, and each group of ramp detectors in the inventory (entrance: merge, or passage and bypass where
there is no merge detector; exit: exit) is assigned to the ramp of the same direction and type that passes closest to
its first loop. The output has one row per ramp: direction, type, latitude and longitude of the merge or diverge point,
OpenStreetMap way, the distance from the detectors to the ramp (ft), and the assigned loops (empty for an unmetered ramp).
"""
import csv
import json
import math
import sys

ON_CORRIDOR = 30  # ft; a ramp end this close to the corridor joins it
MAX_RAMP_LENGTH = 3000  # ft; how far each ramp is followed from the corridor
MAX_DETECTOR_DISTANCE = 300  # ft; largest distance from a ramp detector to its ramp
# auxiliary-lane loops that count exiting traffic (see ReadData.AUXILIARY_EXITS); they are assigned to the nearest exit ramp
AUXILIARY_EXITS = ('8249', '8250')

FT_PER_DEG_LAT = 364000


def ft(a, b):
    """Distance in feet between two (lat, lon) points."""
    return math.hypot((a[0] - b[0]) * FT_PER_DEG_LAT, (a[1] - b[1]) * FT_PER_DEG_LAT * math.cos(math.radians(a[0])))


def dist_to_polyline(p, line):
    best = float('inf')
    k = math.cos(math.radians(p[0]))
    for a, b in zip(line, line[1:]):
        bx, by = (b[1] - a[1]) * FT_PER_DEG_LAT * k, (b[0] - a[0]) * FT_PER_DEG_LAT
        px, py = (p[1] - a[1]) * FT_PER_DEG_LAT * k, (p[0] - a[0]) * FT_PER_DEG_LAT
        L = bx * bx + by * by
        f = max(0, min(1, (px * bx + py * by) / L)) if L > 0 else 0
        best = min(best, math.hypot(px - f * bx, py - f * by))
    return best


def bearing(a, b):
    return math.degrees(math.atan2((b[1] - a[1]) * math.cos(math.radians(a[0])), b[0] - a[0])) % 360


def main():
    geometry = json.load(open(sys.argv[1]))
    ramps_osm = json.load(open(sys.argv[2]))
    inventory = list(csv.reader(open(sys.argv[3], encoding='utf-8-sig')))[1:]

    # corridor ways by direction, using the description tag or the bearing as in ReadData
    corridor = {'EB': [], 'WB': []}
    for w in geometry['elements']:
        if w.get('type') != 'way':
            continue
        line = [(g['lat'], g['lon']) for g in w['geometry']]
        desc = w.get('tags', {}).get('description', '')
        d = 'EB' if 'EB' in desc else 'WB' if 'WB' in desc else ('EB' if 0 < bearing(line[0], line[-1]) < 180 else 'WB')
        corridor[d].append(line)

    ways = [w for w in ramps_osm['elements'] if w.get('type') == 'way']
    # ways are connected where an end of one has the same coordinates as an end of another
    def key(g):
        return (round(g['lat'], 7), round(g['lon'], 7))

    starts, ends = {}, {}
    for w in ways:
        starts.setdefault(key(w['geometry'][0]), []).append(w)
        ends.setdefault(key(w['geometry'][-1]), []).append(w)

    def on_corridor(p, d):
        return min(dist_to_polyline(p, line) for line in corridor[d]) <= ON_CORRIDOR

    def corridor_bearing(p, d):
        """Bearing of the corridor segment nearest to p."""
        best = (float('inf'), 0)
        for line in corridor[d]:
            for a, b in zip(line, line[1:]):
                best = min(best, (dist_to_polyline(p, [a, b]), bearing(a, b)))
        return best[1]

    def angle(a, b):
        return min(abs(a - b) % 360, 360 - abs(a - b) % 360)

    def chain(w, upstream):
        """Coordinates of a ramp from way w, following connected ways away from the corridor."""
        coords = [(g['lat'], g['lon']) for g in w['geometry']]
        if upstream:
            coords = coords[::-1]
        length = sum(ft(a, b) for a, b in zip(coords, coords[1:]))
        node = key(w['geometry'][0]) if upstream else key(w['geometry'][-1])
        seen = {w['id']}
        while length < MAX_RAMP_LENGTH:
            nxt = [v for v in (ends if upstream else starts).get(node, []) if v['id'] not in seen]
            if not nxt:
                break
            v = nxt[0]
            seen.add(v['id'])
            c = [(g['lat'], g['lon']) for g in v['geometry']]
            c = c[::-1] if upstream else c
            length += sum(ft(a, b) for a, b in zip(c, c[1:]))
            coords += c[1:]
            node = key(v['geometry'][0]) if upstream else key(v['geometry'][-1])
        return coords

    ramps = []
    for w in ways:
        coords = [(g['lat'], g['lon']) for g in w['geometry']]
        first, last = coords[0], coords[-1]
        # an entrance ends on the corridor (and does not also start on it); an exit starts on it
        # where the two directions are close together, a ramp end can be near both; the ramp belongs to the direction
        # whose bearing matches the ramp's bearing where it joins
        for t, point, ramp_bearing, upstream in (('Merge', last, bearing(coords[-2], last), True), ('Exit', first, bearing(first, coords[1]), False)):
            other = first if t == 'Merge' else last
            dirs = [d for d in ('EB', 'WB') if on_corridor(point, d) and not on_corridor(other, d)]
            if not dirs:
                continue
            d = min(dirs, key=lambda d: angle(ramp_bearing, corridor_bearing(point, d)))
            ramps.append({'direction': d, 'type': t, 'point': point, 'way': w['id'], 'chain': chain(w, upstream), 'loops': [], 'detector_ft': ''})

    # ramp detector groups by location, as in ReadData
    has_merge = {x[9] for x in inventory if len(x) > 11 and x[11] == 'Merge'}
    groups = {}
    for x in inventory:
        if len(x) < 17 or not x[2].strip() or not x[9].startswith('T.H.610 '):
            continue
        t = x[11]
        if x[2] in AUXILIARY_EXITS:
            t = 'Exit'
        if t in ('Passage', 'Bypass') and x[9] not in has_merge:
            t = 'Merge'
        if t not in ('Merge', 'Exit'):
            continue
        g = groups.setdefault((x[9], t), {'direction': x[9][8:10], 'type': t, 'loops': [], 'points': []})
        if x[2] not in g['loops']:
            g['loops'].append(x[2])
            g['points'].append((float(x[15]), float(x[16])))

    # assign each loop to the nearest ramp of its group's direction and type (a location can have two ramps, e.g. the
    # two entrance ramps of a cloverleaf, each with its own passage detector)
    for (loc, t), g in sorted(groups.items()):
        for loop, p in zip(g['loops'], g['points']):
            cands = [(dist_to_polyline(p, r['chain']), i) for i, r in enumerate(ramps) if r['direction'] == g['direction'] and r['type'] == t]
            dist, i = min(cands)
            if dist > MAX_DETECTOR_DISTANCE:
                print(f'not assigned: {loc} {t} loop {loop}, nearest ramp {dist:.0f} ft away', file=sys.stderr)
                continue
            ramps[i]['loops'].append(loop)
            ramps[i]['detector_ft'] = f"{dist:.0f}" if not ramps[i]['detector_ft'] else ramps[i]['detector_ft'] + ' ' + f"{dist:.0f}"
            ramps[i].setdefault('location', loc)

    with open(sys.argv[4], 'w', newline='') as f:
        out = csv.writer(f)
        out.writerow(['direction', 'type', 'lat', 'lon', 'osm_way', 'location', 'detector_distance_ft', 'loops'])
        for r in sorted(ramps, key=lambda r: (r['direction'], r['point'][1] * (1 if r['direction'] == 'EB' else -1))):
            out.writerow([r['direction'], r['type'], f"{r['point'][0]:.7f}", f"{r['point'][1]:.7f}", r['way'], r.get('location', ''), r['detector_ft'], ' '.join(r['loops'])])


if __name__ == '__main__':
    main()
