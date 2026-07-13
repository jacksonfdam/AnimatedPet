# Animated Pet (Android desktop-pet)

A floating, draggable sprite-sheet pet that lives on top of your home screen and
other apps. **Tap** the pet to open the app; **drag** it to move it around.

This is a standalone experiment — unrelated to any other project in this folder.

## How it works

| Piece | File | Role |
|---|---|---|
| Overlay window | `PetOverlayService.kt` | Foreground service that adds the pet to a `WindowManager` `TYPE_APPLICATION_OVERLAY` window, handles drag vs. tap, and launches the app on tap. |
| Sprite rendering | `SpriteView.kt` | Custom `View` that draws one frame at a time (nearest-neighbour, so pixel art stays crisp) and loops through a frame list. |
| Sheet slicing | `SpriteSheet.kt` | Loads `assets/pet_sheet.png` and slices it into a uniform `cols x rows` grid. `PetAnimations` maps rows to named animations. |
| Multi-pet overlay | `PetOverlayService.kt` | Any number of pets roam the screen in their own direction/speed, face their travel direction, bounce off edges, drag to move, tap to open the app. |
| Live wallpaper | `PetWallpaperService.kt` | Same pets drawn onto the wallpaper canvas, so they appear on the **home and lock screen**. Reads the same selection via `PetPrefs` and updates live. |
| Pet catalog | `PetCatalog.kt` | Auto-discovers every `*.png` in `assets/` as a selectable pet. |
| Control panel | `MainActivity.kt` | Overlay permission, multi-select pets, start/remove, and "Set as live wallpaper". |

## Setup

1. **Add the sprite:** save your sprite sheet as `app/src/main/assets/pet_sheet.png`
   (5 columns × 4 rows expected — see `assets/README.txt`).
2. Open the folder in **Android Studio** (or build from CLI: `./gradlew assembleDebug`).
3. Install on a device/emulator, open the app, tap **Grant overlay permission**,
   then **Start pet**.

## Permissions

- `SYSTEM_ALERT_WINDOW` — draw the pet over other apps (user grants it manually).
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — keep the pet alive.
- `POST_NOTIFICATIONS` — the required foreground-service notification (Android 13+).

## Tweaking the pet

- **Which animation loops:** change `PetAnimations.IDLE` (default = `SIDE`, row 2).
- **Speed:** `PetAnimations.IDLE_FRAME_MS`.
- **On-screen size:** `SpriteView.targetHeightDp`.
- **Grid layout:** the `cols`/`rows` args in `SpriteSheet.load(...)` inside
  `PetOverlayService.addPetToWindow()`.

## Toolchain

Kotlin 2.1.20 · AGP 8.12 · Gradle 9.0 · JDK 21 · min SDK 26 / target 35 / compile 36.
