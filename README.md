# Phantom Rider (Fabric, Minecraft 26.3)

Tame phantoms with any item, saddle them, and fly them like a mount.

## Controls
- Right-click a phantom with **any item** (not a saddle) -> tamed, item consumed
- Right-click a tamed phantom with a **saddle** -> saddled
- **Empty hand** right-click on a saddled phantom -> mount (owner only)
- **Shift + empty hand** right-click -> remove saddle
- Riding: **W/S** move along where you look (pitch included), **A/D** strafe, **Space** climb, **Ctrl** boost, **Shift** dismount
- Tamed phantoms stop attacking, don't burn in sunlight, never despawn, and hover in place when not ridden
- You get Slow Falling for 10 s after dismounting

## Build
Requires JDK 25.
```
gradle wrapper --gradle-version 9.6.0     # one-time, creates gradlew + wrapper jar
./gradlew build
```
Jar: `build/libs/phantom-rider-1.0.0+26.3.jar`. Install with Fabric Loader 0.19.5+ and Fabric API 0.161.0+26.3 (client and server).

Tuning constants are at the top of `PhantomRiderMod.java`.
