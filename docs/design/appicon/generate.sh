#!/usr/bin/env bash
#
# Regenerate every Umamo application-icon asset from the pixel-art master.
#
# Run this by hand whenever the mascot art changes.  It is deliberately NOT part
# of the Gradle build: the build references the committed outputs, and this
# script (re)produces them so contributors without image tooling still build.
#
# Pixel-art crispness: every size is nearest-neighbor scaled.  Upscales use the
# largest integer multiple of the master edge that fits the target, then pad
# transparently to the exact dimension, so every source pixel keeps a uniform
# width (a non-integer scale would make some pixels one device-pixel wider).
# Targets smaller than the master fall back to point sampling.
#
# Requires ImageMagick 7 (`magick`).  ImageMagick writes .ico and .icns itself,
# so no extra packers are needed; icotool (icoutils) / png2icns (libicns) /
# iconutil (macOS) are higher-fidelity alternatives if you prefer them.
#
# Usage: docs/design/appicon/generate.sh [path/to/master.png]
#        (default master: docs/design/appicon/umamo-icon.png)

set -euo pipefail

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/../../.." && pwd)"
master="${1:-$scriptDir/umamo-icon.png}"

if [[ ! -f "$master" ]]; then
	echo "master art not found: $master" >&2
	echo "drop the mascot PNG there (or pass its path) and re-run." >&2
	exit 1
fi

magick="$(command -v magick || command -v convert)"
if [[ -z "$magick" ]]; then
	echo "ImageMagick (magick/convert) not found on PATH." >&2
	exit 1
fi

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

# Solid backdrop composited under the opaque outputs.  The mascot is black line
# art on transparency, so a filled background keeps it visible on dark taskbars and
# launchers and matches the Android adaptive background.  Keep this in sync with the
# ic_launcher_background color resource (values/ic_launcher_background.xml) so a
# re-skin changes both at once.
background="white"

# Crisp nearest-neighbor render of the master at targetEdge x targetEdge, on a
# transparent canvas, filling the frame.  -filter point is pure point sampling (no
# blending), so the pixel-art edges stay hard.  When the target is an integer
# multiple of the master every source pixel keeps a uniform width; the 160px master
# does not integer-divide the standard icon sizes, so filling (minor per-pixel width
# variance at large sizes) is preferred over integer-scale-plus-margin, which would
# size the mascot inconsistently across the frame set.  The master is square; a
# non-square master would fit within the box (letterboxed) rather than fill.
emitTransparent() {
	local targetEdge="$1" outputPath="$2"
	mkdir -p "$(dirname "$outputPath")"
	"$magick" "$master" -filter point -resize "${targetEdge}x${targetEdge}" "$outputPath"
}

# As emitTransparent, but flattened onto the solid backdrop (an opaque square).
emitSquare() {
	local targetEdge="$1" outputPath="$2"
	mkdir -p "$(dirname "$outputPath")"
	"$magick" "$master" -filter point -resize "${targetEdge}x${targetEdge}" \
		-background "$background" -flatten "$outputPath"
}

# Opaque backdrop disc + mascot, transparent outside the circle (legacy round
# launcher bitmap).  round_src is already opaque (emitSquare flattened it), so
# CopyOpacity from the circle mask just carves the disc out of the square.
emitRound() {
	local targetEdge="$1" outputPath="$2"
	emitSquare "$targetEdge" "$scratch/round_src.png"
	"$magick" -size "${targetEdge}x${targetEdge}" xc:none -fill white \
		-draw "circle $((targetEdge/2)),$((targetEdge/2)) $((targetEdge/2)),0" \
		"$scratch/round_mask.png"
	"$magick" "$scratch/round_src.png" "$scratch/round_mask.png" \
		-alpha off -compose CopyOpacity -composite "$outputPath"
}

### Desktop -- jpackage per-OS installer icons --------------------------------
desktopIcons="$repoRoot/app/desktop/icons"
mkdir -p "$desktopIcons"

# Windows .ico (multi-resolution frame set).
windowsSizes=(16 24 32 48 64 128 256)
windowsFrames=()
for size in "${windowsSizes[@]}"; do
	emitSquare "$size" "$scratch/ico_${size}.png"
	windowsFrames+=("$scratch/ico_${size}.png")
done
"$magick" "${windowsFrames[@]}" "$desktopIcons/umamo.ico"

# macOS .icns -- assembled directly.  ImageMagick's icns writer only emits a
# single frame, and png2icns/iconutil are not reliably present, so we build the
# container ourselves: an 'icns' magic + big-endian total length, then one typed
# chunk per size (each chunk is OSType + big-endian length-incl-header + a PNG
# payload, which modern macOS reads directly).  The OSType/size table mirrors the
# standard iconutil .iconset mapping (non-retina icp4/icp5 plus retina ic07..ic14).
# Reference: Apple Icon Image format (icns); iconutil iconset type codes.
icnsSizes=(16 32 64 128 256 512 1024)
for size in "${icnsSizes[@]}"; do
	emitSquare "$size" "$scratch/icns_${size}.png"
done
python3 - "$desktopIcons/umamo.icns" "$scratch" <<'PY'
import os, struct, sys

outputPath, scratchDir = sys.argv[1], sys.argv[2]
# (OSType, pixel size) in the standard iconset order.  Sizes recur across types
# on purpose (icp5/ic11 both 32px, ic08/ic13 both 256px, ic09/ic14 both 512px)
# so both the point-size and its @2x retina slot are populated.
entries = [
	(b"icp4", 16),  (b"icp5", 32),  (b"ic11", 32),  (b"ic12", 64),
	(b"ic07", 128), (b"ic13", 256), (b"ic08", 256), (b"ic14", 512),
	(b"ic09", 512), (b"ic10", 1024),
]
chunks = b""
for osType, size in entries:
	with open(os.path.join(scratchDir, f"icns_{size}.png"), "rb") as pngFile:
		pngBytes = pngFile.read()
	chunks += osType + struct.pack(">I", 8 + len(pngBytes)) + pngBytes
container = b"icns" + struct.pack(">I", 8 + len(chunks)) + chunks
with open(outputPath, "wb") as icnsFile:
	icnsFile.write(container)
PY

# Linux single PNG.
emitSquare 512 "$desktopIcons/umamo.png"

### Desktop -- live window / taskbar icon (Compose resource) ------------------
emitSquare 512 "$repoRoot/module/ui/src/commonMain/composeResources/drawable/app_icon.png"

### Android -- launcher icons -------------------------------------------------
androidRes="$repoRoot/app/android/src/main/res"

# Legacy square/round bitmaps are 48dp; adaptive foreground layers are 108dp.
declare -A legacyEdge=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )
declare -A foregroundEdge=( [mdpi]=108 [hdpi]=162 [xhdpi]=216 [xxhdpi]=324 [xxxhdpi]=432 )

for density in "${!legacyEdge[@]}"; do
	emitSquare "${legacyEdge[$density]}" "$androidRes/mipmap-$density/ic_launcher.png"
	emitRound  "${legacyEdge[$density]}" "$androidRes/mipmap-$density/ic_launcher_round.png"

	# Adaptive foreground: render the mascot (on transparency, so the adaptive
	# background color shows through) into the central ~66dp safe zone of the 108dp
	# layer so the OS mask (circle/squircle/etc.) never clips it.
	fgEdge="${foregroundEdge[$density]}"
	safeEdge=$(( fgEdge * 66 / 108 ))
	emitTransparent "$safeEdge" "$scratch/fg_${density}.png"
	"$magick" "$scratch/fg_${density}.png" -background none -gravity center \
		-extent "${fgEdge}x${fgEdge}" \
		"$androidRes/mipmap-$density/ic_launcher_foreground.png"
done

echo "regenerated Umamo icons from $master"
