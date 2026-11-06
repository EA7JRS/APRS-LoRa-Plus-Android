# WSPR decoder: third-party software

`libwsprdec.so` is a separate program built by `build.sh` from wsprd as kept in https://github.com/Guenael/rtlsdr-wsprd
(commit 1ca9b83): Copyright 2001-2015 Joe Taylor K1JT, 2014-2015 Steven Franke K9AN, 2016 Guenael Jouchet VA2GKA; GPL-3.0.
FFT: KISS FFT (BSD-3-Clause) from ft8_lib, through the small FFTW stand-in `fftw3.h`. `wspr_main.c`, `fftw3.h`,
`test/wspr_gen.c` and `build.sh`: GPL-3.0-or-later, Copyright (C) 2026 Manuel - EA7JRS.
Test on a PC: `native/wspr/build.sh host <dir> test`, then `<dir>/wspr_gen "EA7JRS IM76 23" t.wav 37` and `<dir>/libwsprdec.so t.wav 432300000`.
