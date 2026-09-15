# Third-party components used by AGUS VR

| Component | License | How it is used |
|---|---|---|
| [MediaPipe Tasks Vision](https://developers.google.com/edge/mediapipe) (`com.google.mediapipe:tasks-vision`) | Apache-2.0 | Real hand landmark detection (AgusHandTracking). |
| MediaPipe `hand_landmarker.task` model | Apache-2.0 (Google) | Downloaded at build time by `tools/download_model.gradle`; **not** committed. |
| [Filament](https://github.com/google/filament) (`filament-android`, `gltfio-android`, `filament-utils-android`, `filamat-android`) | Apache-2.0 | 3D rendering engine for AGUS MODEL LAB (GLB/glTF loading, OBJ runtime materials). |
| [AndroidX / CameraX](https://developer.android.com/jetpack/androidx) | Apache-2.0 | Rear-camera passthrough, lifecycle, UI. |
| [JetBrains Kotlin & kotlinx.coroutines](https://kotlinlang.org/) | Apache-2.0 | Language / async runtime. |
| Orbitron font (The League of Moveable Type / Matt McInerney) | SIL Open Font License 1.1 | Display typography. `third_party/licenses/OFL-Orbitron.txt` |
| Manrope font (Mikhail Sharanda) | SIL Open Font License 1.1 | UI typography. `third_party/licenses/OFL-Manrope.txt` |
| JetBrains Mono font (JetBrains) | SIL Open Font License 1.1 | Telemetry/mono typography. `third_party/licenses/OFL-JetBrainsMono.txt` |

## Store content (downloaded by the user at runtime, not bundled)

The AGUS STORE catalog references external projects **by URL**; nothing
third-party is redistributed inside the APK. Each store entry records its
license:

- **2048** — Gabriele Cirulli — MIT — fetched as a source ZIP from
  `github.com/gabrielecirulli/2048` when the user presses Install.
- **HexGL** — BKcore — MIT — fetched as a source ZIP from `github.com/BKcore/HexGL`
  when the user presses Install (heavy WebGL content; requires a modern WebView).
- Web shortcuts (Google, three.js examples, A-Frame examples) simply open the
  public site inside the AGUS Browser — no copy is made.

The bundled games in `app/src/main/assets/games/` (Neon Drift, Astro Pong,
Cube Storm) and the sample 3D models in `app/src/main/assets/samples/` are
**original works created for this project** (MIT).

No third-party Android application (e.g. ZArchiver) is modified, embedded or
redistributed. The file manager only *launches* such apps via public Android
intents when the user explicitly asks for it and the app is already installed.
