# Credits & Third-Party Attributions

Umamo is licensed under the GNU General Public License v3.0.
Project Lead: Azxiana - https://azxiana.com/

## Gylphs

Tabler Icons - MIT License - https://github.com/tabler/tabler-icons/blob/main/LICENSE
Inter Font - SIL Open Font License 1.1 - https://raw.githubusercontent.com/rsms/inter/v4.1/LICENSE.txt
Noto Sans CJK - SIL Open Font License 1.1 - https://github.com/notofonts/noto-fonts/blob/main/LICENSE
Blender Icons - CC BY-SA 4.0 - https://creativecommons.org/licenses/by-sa/4.0/ - https://ui.blender.org/icons
Blender Cursors - GNU General Public License v3.0 - https://projects.blender.org/blender/blender/src/branch/main/doc/license/GPL3-license.txt - https://projects.blender.org/blender/blender/src/branch/main/release/datafiles/cursors

## MOC3 File Format

Umamo's MOC3 file format reader is based on Lina Hoshino's black box reverse engineering of the format.
https://www.youtube.com/@HoshinoLina
https://www.youtube.com/watch?v=eEa0-wt1SqE
https://www.youtube.com/watch?v=iT3A0AhK3YE
https://www.youtube.com/watch?v=5AiZTgUx_WM

## Krita KRA File Format

- Upstream: Krita - https://invent.kde.org/graphics/krita
- License: GNU Public License, Version 3

Umamo's KRA file format reader was ported directly from the official Krita repository.

## Clip Studio Paint CLIP File Format

Umamo's CLIP file format reader is based on research by rasensuihei.
https://github.com/rasensuihei/cliputils
MIT License: https://github.com/rasensuihei/cliputils/blob/master/LICENSE

## TwelveMonkeys ImageIO — PSD, TIFF, and WEBP readers (BSD 3-Clause)

- Upstream: TwelveMonkeys ImageIO, `imageio-psd` module - https://github.com/haraldk/TwelveMonkeys
- Copyright (c) 2008-2020 Harald Kuhr
- License: BSD 3-Clause

- Upstream: TwelveMonkeys ImageIO, `imageio-tiff` (and `imageio-metadata`, `common-io`) modules - https://github.com/haraldk/TwelveMonkeys
- Copyright (c) 2012 Harald Kuhr, Oliver Schmidtmer
- License: BSD 3-Clause

  Covers the LZW, PackBits, horizontal-predictor, and CCITT (Modified Huffman RLE / T.4 / T.6) decoders.
  The IFD parser, pixel assembly, and JPEG-in-TIFF handling are Umamo's own.

- Upstream: TwelveMonkeys ImageIO, `imageio-webp` module - https://github.com/haraldk/TwelveMonkeys
- Copyright (c) 2017 Harald Kuhr, Simon Kammermeier
- License: BSD 3-Clause

Per the BSD 3-Clause terms, the original copyright notice is retained here and in the header of the derived source file.  The ported code is distributed as part of Umamo under GPLv3.

```
BSD 3-Clause License

Copyright (c) 2008-2020, Harald Kuhr
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Baseline JPEG Decoder — Independent JPEG Group

- Upstream: Independent JPEG Group's reference JPEG library (libjpeg) - https://www.ijg.org/
- Copyright (c) 1991-2020, Thomas G. Lane, Guido Vollbeding
- License: IJG License (permissive, GPL-compatible)

Umamo's JPEG decoder (`org.umamo.format.jpeg`) is an independent Kotlin implementation written from the public ITU-T T.81 specification; no IJG source was copied.  Several of its algorithms do, however, follow IJG's reference implementation and fixed-point constants — the accurate integer IDCT of `jidctint.c` (`jpeg_idct_islow`), the triangle chroma upsampling filters of `jdsample.c`, the scaled colour-conversion tables of `jdcolor.c`, and the progressive successive-approximation scan decoders of `jdphuff.c`.  This is deliberate: in a lossy codec the fixed-point rounding IS the output, so matching those algorithms is what makes Umamo decode a JPEG to the same bytes as every mainstream reader.  Accordingly, and per the IJG license's terms for distributing derived work:

> This software is based in part on the work of the Independent JPEG Group.

The decoder is distributed as part of Umamo under GPLv3.

## Bundled Libraries
FileKit - https://github.com/vinceglb/FileKit/blob/main/LICENSE