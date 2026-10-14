# MoneyFlow font notices

MoneyFlow uses **Inter** for interface text and **Inter Display** for large
headings and balances. Both were designed by Rasmus Andersson and contributors.
The static TrueType resources are bundled in the APK; no font download,
Google Play font provider, or network permission is needed to load them.

## Source and license

- Official source: https://github.com/rsms/inter
- Pinned distribution: https://github.com/rsms/inter/releases/tag/v4.1
- Download: https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip
- Distribution SHA-256: `9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`
- SIL Open Font License 1.1, copyright 2016 The Inter Project Authors.
  The complete copyright notice and license ship in `OFL-Inter.txt` next to this file.

The six fonts are byte-for-byte copies of the release's `extras/ttf` files,
renamed only to Android resource filenames. There is no subsetting, conversion,
new glyph drawing, or variable-font instancing. The fonts' internal version
string is `Version 4.001;git-9221beed3` inside the v4.1 distribution.

## Verified Kazakhstan coverage

Every bundled font's Unicode `cmap` was checked with fontTools 4.67.0. All six
contain the complete Russian alphabet including Ёё, all Kazakh Cyrillic additions
**Әә Ғғ Ққ Ңң Өө Ұұ Үү Һһ Іі**, digits **0123456789**, and **₸** (U+20B8).
No system fallback is required for these characters. All six contain the OpenType
`tnum` feature; heading and title styles enable tabular figures so balances
align as their numbers change. Text sizes and line heights use `sp` to honor the
Android user's font scaling.

Static TTF files work on MoneyFlow's minimum API 26 without variable-font
rendering differences. Their combined uncompressed size is 2,506,508 bytes;
no release archive or unused italic/extra weight files are included in the app.

| Android resource | File in pinned distribution | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `inter_regular.ttf` | `extras/ttf/Inter-Regular.ttf` | 411,640 | `40d692fce188e4471e2b3cba937be967878f631ad3ebbbdcd587687c7ebe0c82` |
| `inter_medium.ttf` | `extras/ttf/Inter-Medium.ttf` | 417,300 | `97ad806f526e41546d46365bb3a393145f75b7b1568913db74549ad8b8dba872` |
| `inter_semibold.ttf` | `extras/ttf/Inter-SemiBold.ttf` | 419,744 | `78a843fade9d4612a5567302fb595b56976eb5fcebf4fea5a5912d638bafcde3` |
| `inter_bold.ttf` | `extras/ttf/Inter-Bold.ttf` | 420,428 | `288316099b1e0a47a4716d159098005eef7c0066921f34e3200393dbdb01947f` |
| `inter_display_semibold.ttf` | `extras/ttf/InterDisplay-SemiBold.ttf` | 418,540 | `0310d7a325896129730c6c8cf9a6e0f81ee258bedf77b1ff059b2a7b75f74e02` |
| `inter_display_bold.ttf` | `extras/ttf/InterDisplay-Bold.ttf` | 418,856 | `b74c8e0dd744b3347faca4c96bc7b2e32f7d6f62300a79b1d1a99331e44a5bc4` |
