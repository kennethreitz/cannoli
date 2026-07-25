package dev.cannoli.scorza.libretro

// An unbound hold shortcut is stored as an empty set, and containsAll(emptySet()) is always
// true, so a chord cleared or removed while held must release it explicitly.
fun shouldReleaseHoldShortcut(holdChord: Set<Int>?, pressedKeys: Set<Int>): Boolean =
    holdChord.isNullOrEmpty() || !pressedKeys.containsAll(holdChord)
