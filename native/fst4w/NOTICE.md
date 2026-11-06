# FST4W decoder: third-party software

`libfst4wdec.so` is a separate program built by `build.sh`. `fst4w_dec.c` and `fst4w_common.h` are a C port of the FST4W
path of WSJT-X 3.3.0-beta5 (`lib/fst4_decode.f90`, `lib/fst4/decode240_74.f90`, `fastosd240_74.f90`,
`get_fst4_bitmetrics.f90`, `fst4_baseline.f90`, `get_crc24.f90`, `encode240_74.f90`, `genfst4.f90`, the WSPR payloads of
`lib/77bit/packjt77`): Copyright Joe Taylor K1JT, Steven Franke K9AN and the WSJT-X team; GPL-3.0. `ldpc_240_74.h` holds the
parity and generator tables of WSJT-X (`lib/fst4/ldpc_240_74_parity.f90`, `ldpc_240_74_generator.f90`), written by
`tables.py`. `test/fst4w_gen.c` follows `gen_fst4wave.f90`. FFT: KISS FFT (BSD-3-Clause) from ft8_lib.
The port and `build.sh`, `tables.py`: GPL-3.0-or-later, Copyright (C) 2026 Manuel - EA7JRS.

Left out: noise blanker and Doppler spread measurement. Periods 120, 300, 900 and 1800 s (1800 s needs about 250 MB while it decodes).

Test on a PC: `native/fst4w/build.sh host <dir> test`, then
`<dir>/fst4w_gen t.wav 120 1 1 "EA7JRS IM76 23" 1500 -30 0` and `<dir>/libfst4wdec.so t.wav 14095600 120 1500 100 state.txt`.
Measured with that generator (8 noise seeds each, FST4W-120): -30 dB 8/8, -32 dB 3/8 (5/8 with the call already in
`state.txt`), -33 dB 0/8 (3/8); 15 noise-only files: no false decodes; about 0.7 s per slot on the PC.
