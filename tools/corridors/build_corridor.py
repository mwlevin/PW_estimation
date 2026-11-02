"""Builds the input files of a freeway corridor from OpenStreetMap and the MnDOT detector inventory, like data/MN610.

Usage: python3 tools/corridors/build_corridor.py <corridor> [<output folder>]
where <corridor> is one of ROUTES below (e.g. I94); the output folder defaults to data/<corridor>.

Writes to the output folder:
  geometry.txt     the freeway's motorway ways in each direction, in the Overpass JSON format read by ReadData.readOSM(),
                   with the direction label (e.g. EB) in the description tag
  inventory.csv    the corridor's detectors from data/detectors_20250429.csv, with the direction of the corridor added in
                   front of the location (e.g. "EB|I-494 SB/France Ave"); see DIRECTIONS below
  ramps.csv        entrance and exit ramps with their detectors, like tools/ramps/make_ramps.py
  corridor.properties   the settings read by CorridorConfig

How it works:
1. Detectors: the inventory rows whose location is "<route> <EB|WB|NB|SB>/...", in the metro area (functional RTMC).
2. Geometry: the motorway ways whose ref includes the route, from Overpass. Ways are connected where the end of one is the
   start of another (same coordinates). Each direction of the freeway is the longest chain of connected ways; the second
   direction is the longest chain of the remaining ways. Where the freeway shares the road with another one (e.g. I-94 and
   I-35W in Minneapolis), the ways carry both refs, so the chain continues through.
3. Directions: each mainline station is assigned to the chain nearest to its loops. A route can change direction labels
   along the way (I-494 is SB on its west side, then EB on its south side, then NB on its east side), so the direction of
   a chain is named by the label of most of its stations. Ramp detectors take the direction of the nearest mainline
   station with the same location label.
4. The chains are cut to the stations, plus MARGIN at each end.
5. Ramps: motorway and motorway_link ways that start (exit) or end (entrance) at a point of a chain other than its ends.
   Ramp detectors are assigned to the nearest ramp of their direction and type, as in make_ramps.py.
"""
import csv
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROUTES = {
    'I94': ('I 94', 'I-94'),
    'I35W': ('I 35W', 'I-35W'),
    'I494': ('I 494', 'I-494'),
    'I694': ('I 694', 'I-694'),
    'I394': ('I 394', 'I-394'),
}
INVENTORY = 'data/detectors_20250429.csv'
OVERPASS = 'https://overpass-api.de/api/interpreter'
METRO = (44.60, -94.00, 45.45, -92.70)  # south, west, north, east
MARGIN = 0.5 * 5280  # ft of road kept beyond the first and last stations
# lane types counted as mainline lanes: auxiliary lanes, and HOT/HOV lanes, which OpenStreetMap includes in the number of lanes
MAINLINE_TYPES = ('Mainline', 'Auxiliary', 'HOT', 'HOV')
RAMP_TYPES = ('Merge', 'Exit', 'Passage', 'Bypass')
ON_CORRIDOR = 1  # ft; ramp ends that share a point with the corridor (same coordinates)
MAX_RAMP_LENGTH = 3000  # ft; how far each ramp is followed from the corridor
MAX_DETECTOR_DISTANCE = 300  # ft; largest distance from a ramp detector to its ramp
MAX_STATION_DISTANCE = 150  # ft; stations farther than this from both chains are reported and left out
FT_PER_DEG_LAT = 364000


def ft(a, b):
    return math.hypot((a[0] - b[0]) * FT_PER_DEG_LAT, (a[1] - b[1]) * FT_PER_DEG_LAT * math.cos(math.radians(a[0])))


def project(p, line):
    """(distance in ft from p to the polyline, distance along the polyline in ft to the nearest point)"""
    best, along, total = float('inf'), 0, 0
    k = math.cos(math.radians(p[0]))
    for a, b in zip(line, line[1:]):
        bx, by = (b[1] - a[1]) * FT_PER_DEG_LAT * k, (b[0] - a[0]) * FT_PER_DEG_LAT
        px, py = (p[1] - a[1]) * FT_PER_DEG_LAT * k, (p[0] - a[0]) * FT_PER_DEG_LAT
        L = bx * bx + by * by
        f = max(0, min(1, (px * bx + py * by) / L)) if L > 0 else 0
        d = math.hypot(px - f * bx, py - f * by)
        if d < best:
            best, along = d, total + f * math.sqrt(L)
        total += math.sqrt(L)
    return best, along


def overpass(query, cache):
    if os.path.exists(cache):
        return json.load(open(cache))
    data = urllib.parse.urlencode({'data': query}).encode()
    request = urllib.request.Request(OVERPASS, data=data, headers={'User-Agent': 'PW_estimation-research-script/1.0'})
    for attempt in range(5):
        try:
            text = urllib.request.urlopen(request, timeout=300).read().decode()
            open(cache, 'w').write(text)
            return json.loads(text)
        except Exception as e:  # the public server is often busy
            print(f'Overpass: {e}; retrying', file=sys.stderr)
            time.sleep(30)
    raise RuntimeError('Overpass query failed')


def key(g):
    return (round(g['lat'], 7), round(g['lon'], 7))


def coords(way):
    return [(g['lat'], g['lon']) for g in way['geometry']]


def length(way):
    c = coords(way)
    return sum(ft(a, b) for a, b in zip(c, c[1:]))


def longest_chain(ways):
    """longest sequence of ways in which each starts where the previous one ends"""
    starts = {}
    for w in ways:
        starts.setdefault(key(w['geometry'][0]), []).append(w)
    best = {}  # way id -> (length of the longest chain starting with it, next way)

    def longest(w, visiting):
        if w['id'] in best:
            return best[w['id']][0]
        visiting.add(w['id'])
        result = (length(w), None)
        for v in starts.get(key(w['geometry'][-1]), []):
            if v['id'] not in visiting:
                result = max(result, (length(w) + longest(v, visiting), v['id']), key=lambda r: r[0])
        visiting.discard(w['id'])
        best[w['id']] = result
        return result[0]

    sys.setrecursionlimit(100000)
    by_id = {w['id']: w for w in ways}
    first = max(ways, key=lambda w: longest(w, set()))
    chain, w = [], first
    while w is not None:
        chain.append(w)
        w = by_id.get(best[w['id']][1])
    return chain


def polyline(chain):
    line = []
    for w in chain:
        line += coords(w)[1 if line else 0:]
    return line


def main(corridor, folder):
    ref, route = ROUTES[corridor]
    os.makedirs(folder, exist_ok=True)
    rows = list(csv.reader(open(INVENTORY, encoding='utf-8-sig')))
    header, rows = rows[0], rows[1:]
    pattern = re.compile(re.escape(route) + r' (EB|WB|NB|SB)/')
    rows = [r for r in rows if pattern.match(r[9]) and r[6] == 'RTMC'
            and METRO[0] < float(r[15]) < METRO[2] and METRO[1] < float(r[16]) < METRO[3]]
    lats, lons = [float(r[15]) for r in rows], [float(r[16]) for r in rows]
    bbox = (min(lats) - 0.02, min(lons) - 0.02, max(lats) + 0.02, max(lons) + 0.02)
    print(f'{len(rows)} detectors of {route}; bounding box {bbox}')

    bb = ','.join(f'{x:.4f}' for x in bbox)
    ref_regex = '(^|;) *' + ref + ' *($|;)'
    mainline = overpass(f'[out:json][timeout:240];way["highway"="motorway"]["ref"~"{ref_regex}"]({bb});out geom tags;',
                        os.path.join(folder, 'osm_mainline.json'))
    ways = [w for w in mainline['elements'] if w.get('type') == 'way' and w.get('tags', {}).get('oneway', 'yes') == 'yes']
    chains = [longest_chain(ways)]
    used = {w['id'] for w in chains[0]}
    chains.append(longest_chain([w for w in ways if w['id'] not in used]))
    lines = [polyline(c) for c in chains]
    for c, line in zip(chains, lines):
        print(f'chain of {len(c)} ways, {sum(length(w) for w in c) / 5280:.1f} mi, from {line[0]} to {line[-1]}')

    # mainline stations, by station ID (or location where there is none), assigned to the nearest chain
    stations = {}
    for r in rows:
        if r[11] in MAINLINE_TYPES:
            stations.setdefault(r[3].strip() or r[9], []).append(r)
    station_chain, station_along = {}, {}
    for s, loops in stations.items():
        dist = []
        for line in lines:
            d = [project((float(r[15]), float(r[16])), line) for r in loops]
            dist.append((sum(x[0] for x in d) / len(d), sum(x[1] for x in d) / len(d)))
        i = min((0, 1), key=lambda i: dist[i][0])
        if dist[i][0] > MAX_STATION_DISTANCE:
            print(f'station {s} ({loops[0][9]}) is {dist[i][0]:.0f} ft from the corridor; left out', file=sys.stderr)
            continue
        station_chain[s], station_along[s] = i, dist[i][1]

    # name each chain by the direction label of most of its stations
    labels = []
    for i in (0, 1):
        counts = {}
        for s, c in station_chain.items():
            if c == i:
                d = stations[s][0][9].split('/')[0].split(' ')[-1]
                counts[d] = counts.get(d, 0) + len(stations[s])
        labels.append(max(counts, key=counts.get))
        print(f'chain {i} is {labels[-1]}: loops by label {counts}')
    if labels[0] == labels[1]:
        raise RuntimeError('both directions have the same label')

    # cut each chain to its stations plus MARGIN
    for i in (0, 1):
        positions = [station_along[s] for s, c in station_chain.items() if c == i]
        lo, hi = min(positions) - MARGIN, max(positions) + MARGIN
        kept, start = [], 0
        for w in chains[i]:
            end = start + length(w)
            if end > lo and start < hi:
                kept.append(w)
            start = end
        chains[i] = kept
        lines[i] = polyline(kept)
        print(f'{labels[i]}: {len(kept)} ways, {sum(length(w) for w in kept) / 5280:.1f} mi')

    # direction of every detector: mainline loops from their station; ramp loops from the nearest station with the same label
    direction = {}
    for s, c in station_chain.items():
        for r in stations[s]:
            direction[id(r)] = labels[c]
    for r in rows:
        if id(r) in direction or r[11] not in RAMP_TYPES:
            continue
        p = (float(r[15]), float(r[16]))
        same = [s for s in station_chain if stations[s][0][9].split('/')[0] == r[9].split('/')[0]]
        if same:
            s = min(same, key=lambda s: ft(p, (float(stations[s][0][15]), float(stations[s][0][16]))))
            direction[id(r)] = labels[station_chain[s]]

    with open(os.path.join(folder, 'inventory.csv'), 'w', newline='') as f:
        out = csv.writer(f)
        out.writerow(header)
        for r in rows:
            if id(r) in direction:
                out.writerow(r[:9] + [direction[id(r)] + '|' + r[9]] + r[10:])

    write_geometry(os.path.join(folder, 'geometry.txt'), chains, labels)
    write_ramps(os.path.join(folder, 'ramps.csv'), folder, bb, chains, labels, rows, direction)

    with open(os.path.join(folder, 'corridor.properties'), 'w') as f:
        f.write(f'# made by tools/corridors/build_corridor.py {corridor}\n')
        f.write(f'name={route}\n')
        f.write(f'geometry={folder}/geometry.txt\n')
        f.write(f'inventory={folder}/inventory.csv\n')
        f.write(f'ramps={folder}/ramps.csv\n')
        f.write(f'prior={folder}/ramp_prior.csv\n')
        f.write(f'labels={",".join(labels)}\n')
        f.write(f'detector_labels={",".join(l + "|" for l in labels)}\n')


def write_geometry(path, chains, labels):
    """Overpass JSON as formatted by the Overpass server, which ReadData.readOSM() reads line by line"""
    with open(path, 'w') as f:
        f.write('{\n  "version": 0.6,\n  "generator": "tools/corridors/build_corridor.py",\n  "elements": [\n')
        items = []
        for chain, label in zip(chains, labels):
            for w in chain:
                tags = {k: v for k, v in w.get('tags', {}).items() if k in ('highway', 'lanes', 'maxspeed', 'ref', 'name', 'oneway')}
                if 'lanes' in tags and not tags['lanes'].isdigit():
                    del tags['lanes']
                if 'maxspeed' in tags and not re.match(r'^\d+ mph$', tags['maxspeed']):
                    del tags['maxspeed']
                tags['description'] = label
                geometry = ',\n'.join(f'    {{ "lat": {lat:.7f}, "lon": {lon:.7f} }}' for lat, lon in coords(w))
                tag_text = ',\n'.join(f'    "{k}": "{v}"' for k, v in sorted(tags.items()))
                items.append(f'{{\n  "type": "way",\n  "id": {w["id"]},\n  "geometry": [\n{geometry}\n  ],\n  "tags": {{\n{tag_text}\n  }}\n}}')
        f.write(',\n'.join(items))
        f.write('\n  ]\n}\n')


def write_ramps(path, folder, bb, chains, labels, rows, direction):
    osm = overpass(f'[out:json][timeout:240];way["highway"~"^(motorway|motorway_link)$"]({bb});out geom tags;',
                   os.path.join(folder, 'osm_ramps.json'))
    corridor_ids = {w['id'] for c in chains for w in c}
    ways = [w for w in osm['elements'] if w.get('type') == 'way' and w['id'] not in corridor_ids]
    starts, ends = {}, {}
    for w in ways:
        starts.setdefault(key(w['geometry'][0]), []).append(w)
        ends.setdefault(key(w['geometry'][-1]), []).append(w)

    # points of each chain, except its two ends (where the corridor itself continues)
    points = []
    for chain in chains:
        pts = set()
        for w in chain:
            pts.update(key(g) for g in w['geometry'])
        pts.discard(key(chain[0]['geometry'][0]))
        pts.discard(key(chain[-1]['geometry'][-1]))
        points.append(pts)

    def follow(w, upstream):
        c = coords(w)[::-1] if upstream else coords(w)
        total = sum(ft(a, b) for a, b in zip(c, c[1:]))
        node = key(w['geometry'][0]) if upstream else key(w['geometry'][-1])
        seen = {w['id']}
        while total < MAX_RAMP_LENGTH:
            nxt = [v for v in (ends if upstream else starts).get(node, []) if v['id'] not in seen]
            if not nxt:
                break
            v = nxt[0]
            seen.add(v['id'])
            d = coords(v)[::-1] if upstream else coords(v)
            total += sum(ft(a, b) for a, b in zip(d, d[1:]))
            c += d[1:]
            node = key(v['geometry'][0]) if upstream else key(v['geometry'][-1])
        return c

    ramps = []
    for w in ways:
        first, last = key(w['geometry'][0]), key(w['geometry'][-1])
        for i, label in enumerate(labels):
            if last in points[i] and first not in points[i]:
                ramps.append({'direction': label, 'type': 'Merge', 'point': last, 'way': w['id'], 'chain': follow(w, True),
                              'lanes': w.get('tags', {}).get('lanes', '1'), 'loops': [], 'detector_ft': ''})
            if first in points[i] and last not in points[i]:
                ramps.append({'direction': label, 'type': 'Exit', 'point': first, 'way': w['id'], 'chain': follow(w, False),
                              'lanes': w.get('tags', {}).get('lanes', '1'), 'loops': [], 'detector_ft': ''})

    has_merge = {r[9] for r in rows if r[11] == 'Merge'}
    groups = {}
    for r in rows:
        if id(r) not in direction or r[11] not in RAMP_TYPES:
            continue
        t = 'Merge' if r[11] in ('Passage', 'Bypass') and r[9] not in has_merge else r[11]
        if t not in ('Merge', 'Exit'):
            continue
        g = groups.setdefault((r[9], t, direction[id(r)]), {'loops': [], 'points': []})
        if r[2] not in g['loops']:
            g['loops'].append(r[2])
            g['points'].append((float(r[15]), float(r[16])))
    for (loc, t, d), g in sorted(groups.items()):
        for loop, p in zip(g['loops'], g['points']):
            cands = [(project(p, r['chain'])[0], i) for i, r in enumerate(ramps) if r['direction'] == d and r['type'] == t]
            if not cands:
                continue
            dist, i = min(cands)
            if dist > MAX_DETECTOR_DISTANCE:
                print(f'not assigned: {loc} {t} loop {loop}, nearest ramp {dist:.0f} ft away', file=sys.stderr)
                continue
            ramps[i]['loops'].append(loop)
            ramps[i]['detector_ft'] = (ramps[i]['detector_ft'] + ' ' if ramps[i]['detector_ft'] else '') + f'{dist:.0f}'
            ramps[i].setdefault('location', d + '|' + loc)

    # order along each chain
    lines = [polyline(c) for c in chains]
    for r in ramps:
        r['along'] = project(r['point'], lines[labels.index(r['direction'])])[1]
    with open(path, 'w', newline='') as f:
        out = csv.writer(f)
        out.writerow(['direction', 'type', 'lat', 'lon', 'osm_way', 'location', 'detector_distance_ft', 'loops', 'lanes'])
        for r in sorted(ramps, key=lambda r: (r['direction'], r['along'])):
            out.writerow([r['direction'], r['type'], f"{r['point'][0]:.7f}", f"{r['point'][1]:.7f}", r['way'], r.get('location', ''),
                          r['detector_ft'], ' '.join(r['loops']), r['lanes']])
    metered = sum(1 for r in ramps if r['loops'])
    print(f'{len(ramps)} ramps, {metered} with detectors')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else os.path.join('data', sys.argv[1]))
