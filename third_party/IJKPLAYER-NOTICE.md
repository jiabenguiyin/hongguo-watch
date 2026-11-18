# IJKPlayer natives / Java binding

- Java API: derived from bilibili/ijkplayer (LGPL/GPL) via public Android ports.
- Native `armeabi-v7a` + `arm64-v8a` `libijkffmpeg.so` / `libijkplayer.so` / `libijksdl.so`:
  taken from the public fiillplayer ijkplayer-java jniLibs (FFmpeg soft decode includes `ff_hevc_decoder`).
- Reference player option patterns studied from a GPL-3.0 watch client that uses IJK soft decode on low-end SoCs.
- Upstream bytevc1 (ByteDance HEVC profile) may not decode even when standard HEVC works; diagnose via real streams + uploaded playback diagnostics.

Do not ship product names of third-party clients in UI/PR copy.
