# ForgeROM community catalogue

ForgeROM fetches [devices.json](devices.json) from the public GitHub raw URL at runtime:

https://raw.githubusercontent.com/RectifyData/ForgeROM/main/database/devices.json

Device records are cached locally with Google's public device catalogue as a fallback. When a user searches for a ROM, ForgeROM also reads curated ROM listings from this file and keeps a copy available offline.

## Device entries

Maintain `schema_version: 1`. Each device needs `brand`, `marketing`, `codename`, and an array of exact `models`. Use manufacturer's documentation or established device sources to identify the correct model and codename. Mismatched model numbers are intentionally excluded.

## ROM listings

Optional `roms` under a device supports:

- `name`, `android_version`, `status`, `source_url`, `notes`, `models`
- `packages`: ROM ZIPs or official release posts, with `label`, `version`, `file_name`, `url`, and optional `direct_url`
- `extra_images`: separate boot, vendor, recovery, or other partition files. Use `partition` alongside `label`, `file_name`, `version`, `url`, and optional `direct_url`
- `bugs`: an array of `component`, `status` (`broken`, `unsupported`, `untested`, or `fixed`), and `details`
- `warnings` and `credits`: arrays of concise strings

Example structure:

```json
{
  "brand": "Samsung",
  "marketing": "Galaxy J7 Prime",
  "codename": "on7xelte",
  "models": ["SM-G610F", "SM-G610F/DS"],
  "roms": [{
    "name": "Example Android port",
    "android_version": "15",
    "status": "unstable",
    "source_url": "https://example.org/project/release",
    "packages": [{
      "label": "ROM",
      "version": "v1.0",
      "file_name": "rom.zip",
      "url": "https://example.org/download"
    }],
    "extra_images": [{
      "label": "Vendor image",
      "partition": "vendor",
      "file_name": "vendor.img",
      "version": "v1.0",
      "url": "https://example.org/vendor"
    }],
    "bugs": [{"component": "Camera", "status": "broken", "details": "Reported by maintainer"}],
    "warnings": ["Back up your device and verify the exact model and release before installing"],
    "credits": ["ROM maintainer"]
  }]
}
```

The app displays these fields in the **Info | Downloads/installs | Bugs** tabs of each ROM card. Each artifact opens its source link in the browser. A `direct_url` is always labelled as an **unverified mirror**. No link in this database can initiate flashing, erase partitions, grant root access, or authorize installation. Missing bugs mean **no reports**, not a bug-free ROM.

## Review rules

Keep the exact ROM version alongside its kernel and vendor images. Do not imply that images from an older release work with a newer ZIP. Record unsupported storage variants and failed features explicitly. Use trusted HTTPS sources where possible; raw JSON and mirrors are not cryptographically verified. Any proposed root-based install still requires a separately verified image and device-specific recovery instructions.
