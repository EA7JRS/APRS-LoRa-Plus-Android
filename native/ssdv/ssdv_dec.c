/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * SSDV decoder entry for the app. Reads 256-byte SSDV packets (UKHAS format, as the tracker / RadioLib emit) from a file and
 * writes the reconstructed baseline JPEG. Built as lib/<abi>/libssdvdec.so and run by the app:
 *     libssdvdec.so <packets.bin> <out.jpg>
 * stderr: "packets=<n> id=<id> res=<w>x<h> call=<callsign> jpeg=<bytes>". The upstream ssdv.c tolerates missing packets, so
 * a partial image still decodes.
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include "ssdv.h"

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s packets.bin out.jpg\n", argv[0]); return 2; }
    FILE* fin = fopen(argv[1], "rb");
    if (!fin) { fprintf(stderr, "cannot open %s\n", argv[1]); return 3; }

    ssdv_t s;
    ssdv_dec_init(&s);
    size_t cap = 2 * 1024 * 1024;
    uint8_t* jpeg = (uint8_t*)malloc(cap);
    if (!jpeg) { fclose(fin); return 5; }
    ssdv_dec_set_buffer(&s, jpeg, cap);

    uint8_t pkt[SSDV_PKT_SIZE];
    int errors, n = 0, id = -1, w = 0, h = 0;
    char call[16] = {0};
    while (fread(pkt, 1, SSDV_PKT_SIZE, fin) == SSDV_PKT_SIZE) {
        if (ssdv_dec_is_packet(pkt, &errors) != 0) continue;       /* validate + Reed-Solomon correct; skip bad packets */
        ssdv_packet_info_t p;
        ssdv_dec_header(&p, pkt);
        id = p.image_id; w = p.width; h = p.height;
        snprintf(call, sizeof(call), "%s", p.callsign_s);
        ssdv_dec_feed(&s, pkt);
        n++;
    }
    fclose(fin);

    uint8_t* out = NULL;
    size_t len = 0;
    ssdv_dec_get_jpeg(&s, &out, &len);
    if (len == 0) { fprintf(stderr, "no image\n"); free(jpeg); return 1; }

    FILE* fo = fopen(argv[2], "wb");
    if (!fo) { free(jpeg); return 4; }
    fwrite(out, 1, len, fo);
    fclose(fo);
    fprintf(stderr, "packets=%d id=%d res=%dx%d call=%s jpeg=%zu\n", n, id, w, h, call, (size_t)len);
    free(jpeg);
    return 0;
}
