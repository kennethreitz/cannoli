# RomM Save Bundles

Cannoli synchronizes ordinary in-game saves with RomM. These bundles are not
emulator save states or snapshots.

## Cemu

A portable Cemu save is a ZIP archive with this layout:

```text
cannoli-standalone-save.txt
save/
  ...the contents of this title's Cemu save directory...
```

The UTF-8 manifest has exactly these fields and a trailing newline:

```ini
format=1
emulator=CEMU
title_id=0005000010145C00
```

- `format` is currently `1`.
- `emulator` is the case-sensitive standalone-emulator identifier `CEMU`.
- `title_id` is the game's uppercase, 16-character hexadecimal Wii U title ID.
- `save/` contains the files and directories found inside
  `mlc01/usr/save/<high-title-id>/<low-title-id>/`; it does not contain the
  `mlc01` or per-title directory itself.
- Every other regular ZIP entry must be below `save/`. Empty bundles are
  invalid.

For example, title ID `0005000010145C00` maps to Cemu's native directory:

```text
mlc01/usr/save/00050000/10145c00/
```

and the contents of that directory become the archive's `save/` tree.

## Vita3K

A portable Vita3K save uses the same marker plus `save/` structure:

```text
cannoli-standalone-save.txt
save/
  ...the contents of ux0/user/00/savedata/<title-id>/...
```

For Super Stardust Delta, the marker is:

```ini
format=1
emulator=VITA3K
title_id=PCSA00006
```

Cannoli also accepts two representations used by RetroVault and Vita3K tools:

- a ZIP rooted directly at the selected title's save directory; and
- a native ZIP rooted at `ux0/user/00/savedata/<title-id>/` (optionally below
  a leading `vita/` directory).

Native multi-title exports are filtered to the title associated with the RomM
game. Direct bundles rely on that RomM game association because they contain
no title identifier. Paths, entry counts, expanded size, portable markers, and
native title IDs are validated before Cannoli replaces Vita3K's live save.

## Round-trip preservation

A client may translate the portable tree into its native emulator layout while
the game is running. If it later uploads an updated save, it should use the
same representation it originally received:

- A Cannoli portable bundle remains a Cannoli portable bundle.
- A native desktop Cemu MLC bundle remains a native MLC bundle.
- When preserving a Cannoli bundle, retain the original manifest bytes and
  replace only the files beneath `save/` with the newly written game save.

This format-stable round trip lets Android and desktop clients share save data
without requiring either client to understand the other's private runtime
layout.
