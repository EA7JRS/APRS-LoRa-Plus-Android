#!/usr/bin/env python3
# Copyright (C) 2026 Manuel - EA7JRS
# SPDX-License-Identifier: GPL-3.0-or-later
#
# VOR list for the app from OurAirports (navaids.csv, public domain, https://github.com/davidmegginson/ourairports-data):
#   tools/navaids/make_vor.py <navaids.csv> app/src/main/assets/navaids/vor.csv
# Output, one per line: ident;name;type;kHz;lat;lon;elevation ft;country
import csv, sys
src, dst = sys.argv[1], sys.argv[2]
types = {"VOR", "VOR-DME", "VORTAC", "DVOR", "DVOR-DME"}
rows = []
for r in csv.DictReader(open(src, encoding="utf-8")):
    if r["type"] not in types or not r["frequency_khz"] or not r["latitude_deg"]:
        continue
    name = r["name"].replace(";", ",")
    rows.append("%s;%s;%s;%s;%.5f;%.5f;%s;%s" % (r["ident"], name, r["type"], r["frequency_khz"], float(r["latitude_deg"]),
                                                float(r["longitude_deg"]), r["elevation_ft"] or "", r["iso_country"]))
with open(dst, "w", encoding="utf-8") as f:
    f.write("# VOR from OurAirports navaids.csv (public domain); ident;name;type;kHz;lat;lon;elevation ft;country\n")
    f.write("\n".join(rows) + "\n")
print(len(rows), "VOR")
