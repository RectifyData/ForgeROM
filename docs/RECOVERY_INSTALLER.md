# Root-assisted recovery installation

ForgeROM provides a **guided recovery handoff**, not universal automatic flashing.

When root is granted, the Install tab can stage a user-selected ROM ZIP to `Downloads/ForgeROM` on Android 10+ after verifying its **published SHA-256**. Users must confirm that they have backed up their data, identified the correct ROM and checked their actual recovery/firmware. They can then explicitly choose to reboot into custom recovery and manually install the verified ZIP there.

The app never writes firmware partitions directly, never silently triggers `install` scripts and never assumes that a model/codename listing makes a ROM compatible. The normal downloader continues to work without root. For Android 8–9, manual ZIP placement is required due to legacy storage permission differences.
