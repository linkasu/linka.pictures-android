# FFmpeg 8.1.2 — exact upstream source delivery

[`ffmpeg-8.1.2.tar.xz`](ffmpeg-8.1.2.tar.xz) is the unchanged 11,710,924-byte upstream archive originally
authenticated in this project's source intake. SHA256:
`464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c`.
It was copied from that existing authenticated intake, not fetched again.

Original URLs: https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz and its `.asc`;
public release key: https://ffmpeg.org/ffmpeg-devel.asc. Pinned fingerprint:
`FCF986EA15E6E293A5644F10B4322F04D67658D8`.
The armored and dearmored public keys contain no private key material.
Production verifies all vendored bytes and runs `gpgv` with an isolated empty
homedir and this public key; it does not read a personal GPG keyring.

`COPYING.LGPLv2.1` and `LICENSE.md` are exact unchanged members of the tar.
Keep all per-file copyright and other license notices inside the full archive.
No upstream patch is applied by the production build.

This source delivery is not a patent opinion, app-license grant, written offer,
or legal/store approval. The build uses LGPL2.1-or-later configuration with GPL,
nonfree, version3 and network disabled. It does not claim ownership of FFmpeg.

Build, modification and runtime replacement instructions:
[`docs/development/ios-ffmpeg-runtime.md`](../../docs/development/ios-ffmpeg-runtime.md).
