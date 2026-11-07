# Credits & Third-Party Attributions

Umamo is licensed under the GNU General Public License v3.0.
Project Lead: Azxiana - https://azxiana.com/

## Translations
한국어/Korean - Thank you to Nyaro for the translation.
Français/French - Thank you to skueee(Robin Brams) for the translation.

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

## TwelveMonkeys ImageIO - PSD, TIFF, and WEBP readers (BSD 3-Clause)

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

## Robust Geometric Predicates (Public Domain)

- Upstream: robust-predicates - https://github.com/mourner/robust-predicates
- Author: Volodymyr Agafonkin
- License: The Unlicense (Public Domain Dedication)

- Original: Jonathan Richard Shewchuk, "Adaptive Precision Floating-Point Arithmetic and Fast Robust Geometric Predicates" (1997) and its `predicates.c` - https://www.cs.cmu.edu/~quake/robust.html
- License: Placed in the public domain by the author.

Umamo's exact `orient2d` and `incircle` (`org.umamo.geometry.predicate`) and the expansion arithmetic beneath them are ported from robust-predicates' `orient2d.js`, `incircle.js`, and `util.js`, with Shewchuk's original sign convention kept (robust-predicates negates `orient2d` for a downward y axis).  The triangulator built on them is Umamo's own, written from the published papers.

## Other Algorithm Research Papers

Shewchuk 1997, "Adaptive Precision Floating-Point Arithmetic and Fast Robust Geometric Predicates", Discrete & Computational Geometry Volume 18 Issue 3
https://people.eecs.berkeley.edu/~jrs/papers/robustr.pdf

Lawson 1977, "Software for C1 surface interpolation"

Sloan 1993, "A fast algorithm for generating constrained Delaunay triangulations", Computers & Structures Volume 47 Issue 3

Felzenszwalb and Huttenlocher 2012, "Distance Transforms of Sampled Functions", Theory of Computing Volume 8 Issue 19

Douglas and Peucker 1973 Cartographica Volume 10 Issue 2
https://en.wikipedia.org/wiki/Ramer%E2%80%93Douglas%E2%80%93Peucker_algorithm

Igarashi, Moscovich, and Hughes 2005, "As-Rigid-As-Possible Shape Manipulation"
https://www-ui.is.s.u-tokyo.ac.jp/~takeo/papers/rigid.pdf

Ruppert 1995, Journal of Algorithms Volume 18 Issue 3

Üngör 2004 and 2009, arXiv cs/0501007
https://arxiv.org/abs/cs/0501007

Shewchuk 2002, "Delaunay refinement algorithms for triangular mesh generation"
https://pdf.sciencedirectassets.com/271512/1-s2.0-S0925772114X00040/1-s2.0-S0925772114000388/main.pdf

Miller, Pav, and Walkington 2003
https://www.researchgate.net/publication/2552561_Fully_Incremental_3D_Delaunay_Refinement_Mesh_Generation

Garland and Heckbert 1995, "Fast Polygonal Approximation of Terrains and Height Fields"
https://www.cs.cmu.edu/afs/cs/Web/People/garland/scape/scape.pdf

Prasad 1997, "Rectification of the chordal axis transform and a new criterion for shape decomposition"
https://www.osti.gov/servlets/purl/977914

Igarashi, Matsuoka, and Tanaka 1999, "Teddy: A Sketching Interface for 3D Freeform Design"
https://www.cs.toronto.edu/~jacobson/seminar/igarashi-et-al-1999.pdf

## Baseline JPEG Decoder - Independent JPEG Group

- Upstream: Independent JPEG Group's reference JPEG library (libjpeg) - https://www.ijg.org/
- Copyright (c) 1991-2020, Thomas G. Lane, Guido Vollbeding
- License: IJG License (permissive, GPL-compatible)

Umamo's JPEG decoder (`org.umamo.format.jpeg`) is an independent Kotlin implementation written from the public ITU-T T.81 specification.  Accordingly, and per the IJG license's terms for distributing derived work:

> This software is based in part on the work of the Independent JPEG Group.

The decoder is distributed as part of Umamo under GPLv3.

## Bundled Libraries
FileKit - https://github.com/vinceglb/FileKit/blob/main/LICENSE