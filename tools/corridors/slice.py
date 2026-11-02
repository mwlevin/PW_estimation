"""Cuts a period out of a detector data file (e.g. a day from extract.sh) so that the period starts at time 0 of the model.

Usage: python3 tools/corridors/slice.py <detector data CSV> <start HH:MM> <end HH:MM> <output CSV>
The output has the 3 header rows and the 30-second rows that end after start and no later than end.
"""
import sys


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


def main(source, start, end, output):
    start, end = seconds(start), seconds(end)
    with open(source) as f, open(output, 'w') as out:
        for i, line in enumerate(f):
            if i < 3:
                out.write(line)
                continue
            t = seconds(line.split(',', 2)[1])
            if start < t <= end:
                out.write(line)


if __name__ == '__main__':
    main(*sys.argv[1:5])
