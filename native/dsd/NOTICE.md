# Digital voice decoder: third-party software

`libdsdfme.so` is a separate program (not linked into the app) built by `build.sh` from these sources, at fixed commits:

| Component | Source | Licence |
|---|---|---|
| dsd-fme | https://github.com/lwvmobile/dsd-fme | ISC (DSD Author); `dstar_header`, `descramble.h`, `fcs.h`: GPL-2.0 (ON1ARF, G4KLX); other parts as stated in its `COPYRIGHT` |
| mbelib | https://github.com/szechyjs/mbelib | ISC. Its author warns that the AMBE / IMBE methods may be covered by patents (DVSI): personal use; check before publishing the app |
| libsndfile | https://github.com/libsndfile/libsndfile | LGPL-2.1-or-later (static; the build is reproducible from `build.sh`) |
| ncurses | https://github.com/mirror/ncurses | MIT-X11 |
| PulseAudio stand-in, `compat/`, `CMakeLists.txt`, `build.sh` | this repository | GPL-3.0-or-later, Copyright (C) 2026 Manuel - EA7JRS |

The app starts it with TCP input and stdout output only and never passes decryption keys.
