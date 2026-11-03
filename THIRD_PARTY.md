# Third-party material

Code and assets that come from outside Samosa AI, with their licenses (plan §6: "Code copied from a
launcher keeps its license header and gets a line here"). Code adapted from the private prototype is Samosa AI's own
and isn't listed.

| What | Where | From | License |
|---|---|---|---|
| Figtree typeface | `app/src/main/res/font/figtree.ttf` | The Figtree Project Authors, via the private prototype | SIL Open Font License 1.1 (`app/src/main/assets/licenses/OFL_figtree.txt`) |
| Inter typeface (variable, opsz + wght) | `app/src/main/res/font/inter.ttf` | The Inter Project Authors (github.com/rsms/inter), via the Google Fonts repository | SIL Open Font License 1.1 (`app/src/main/assets/licenses/OFL_inter.txt`) |
| Space Grotesk typeface (variable, wght) | `app/src/main/res/font/space_grotesk.ttf` | The Space Grotesk Project Authors (github.com/floriankarsten/space-grotesk), via the Google Fonts repository | SIL Open Font License 1.1 (`app/src/main/assets/licenses/OFL_space_grotesk.txt`) |
| compose-richtext (`richtext-ui-material3`, `richtext-commonmark` 0.17.0) | Gradle dependency used by `app/.../ui/components/Markdown.kt` to render chat Markdown | The compose-richtext authors | Apache License 2.0 |
| AndroidSVG 1.4 | Gradle dependency used by `app/.../miniapps/MiniAppIcon.kt` to render agent-supplied SVG icons | The AndroidSVG authors (github.com/BigBadaboom/androidsvg) | Apache License 2.0 |
| Meta SAM segmentation mask decoder (base85 + range coder + predictors) | `app/src/main/kotlin/com/samosaai/masala/tools/sam/MaskCodec.kt`, a Kotlin port of `meta_sam_parser/_mask_codec.py` | Meta Platforms, Inc. (github.com/meta-models/meta-sam, PyPI `meta-sam-parser`) | SAM License |

Launcher designs studied but not copied (GPL): Olauncher's search normalization and CJK typing guard,
and mLauncher's FuzzyFinder scoring, are reimplemented from their description in
`core/src/main/kotlin/com/samosaai/masala/core/apps/AppSearch.kt`.
