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
- `extra_zips`: separate required or optional recovery ZIPs, such as repartitioners, firmware, patches and add-ons. Each entry takes `label`, `kind`, `required` (defaults to `true`), optional `file_name`, `version`, `url`, `direct_url`, `documentation_url`, `notes`, published `sha256`, and exact `models`. A prerequisite without a confirmed download URL should **omit** `url`, but can link to its maintainer's instructions via `documentation_url`. The app shows it as **missing**, never as downloadable.
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
    "extra_zips": [{
      "label": "Device-specific repartitioner",
      "kind": "repartitioner",
      "required": true,
      "documentation_url": "https://example.org/repartition-instructions",
      "notes": "No verified standalone ZIP available"
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

ZIP metadata is **display-only**. An HTTPS source link, published SHA-256, or maintainer statement is not a flashing authorization. Repartitioning tools may destroy data; never trigger installation, partition changes, or root commands from a remote catalogue entry. For dependencies that vary by model, set `models` on the individual `extra_zips` item. Show a missing link rather than inventing or substituting one.

## Public folder hosts (GoFile and similar)

For packages, partition images, and required/optional ZIPs, a listing can additionally provide:

- `folder_url`: the original HTTPS folder URL, opened in the user's browser
- `public_password`: only a password the file owner explicitly published for public access
- `password_source_url` (optional): an HTTPS link to the owner's announcement, if available

All three can be supplied alongside the regular `url`; for folder-only entries, a package can omit `url` (the original folder becomes its link). For an extra ZIP, a folder URL also counts as a reference to the file, not an automatically downloadable ZIP.

Example for a *fictional* public folder:

```json
{
  "label": "ROM download folder",
  "version": "v1",
  "folder_url": "https://gofile.io/d/EXAMPLE",
  "public_password": "EXAMPLE_OWNER_SHARED_CODE",
  "password_source_url": "https://example.org/maintainer-public-post"
}
```

The app shows the original folder button, displays the publicly shared password and lets the user copy it. The password is shown directly from the curated database without requiring a separate public source URL. If `password_source_url` is present, the app offers it as an optional reference; invalid source URLs are ignored without hiding the password. Do not add personal logins, nonpublic credentials, tokens, or guessed passwords. The app neither attempts debrid/unlock services nor enters passwords on behalf of the user. No folder entry authorizes flashing or repartitioning.
