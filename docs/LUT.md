# Lomo LUT provenance

Lomo Camera uses a colour lookup table (LUT) to create its single built-in photographic look.

## Clean-room derivation

The LUT was created by comparing user-supplied photographs from a Pixel 8 with corresponding versions processed using the Lomo effect in Camera ZOOM FX. The input and output images were aligned and corresponding pixels were analysed to estimate the visible colour/tone transformation.

The calibration showed a repeatable cross-processed look: cooler blue/purple shadows, warmer yellow/orange highlights, increased colour separation and saturation, and a modest contrast shift. A new RGB mapping was then fitted from those observations.

No Camera ZOOM FX source code, native libraries, shaders, LUTs, artwork or other application assets are included in Lomo Camera, and none were copied into this project. The project reproduces only the observed visual character using independently written code and independently generated calibration data.

## Runtime asset

The fitted calibration was initially represented as a 33×33×33 RGB cube. The app ships a compact 17×17×17 resampling of that mapping as:

`app/src/main/res/drawable-nodpi/lomo_lut.png`

The atlas contains blue slices laid out from left to right; within each slice the horizontal axis is red and the vertical axis is green.

For the live preview, Android RuntimeShader/AGSL samples the LUT with trilinear interpolation. For saved photographs, the app expands the compact LUT into a 64×64×64 in-memory lookup table at startup so full-resolution processing is fast while retaining smooth colour transitions.

## Later tuning

After the initial fit, small tone and colour corrections were made by comparing Lomo Camera output against Camera ZOOM FX output from approximately matched Pixel 8 scenes. Those adjustments were deliberately limited to midtones/highlights; the characteristic cool shadow treatment was largely preserved.

## Licence

The generated LUT asset and the code that uses it are distributed as part of Lomo Camera under the repository's GNU General Public License v3.0 (`GPL-3.0-only`).
