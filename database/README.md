# ForgeROM phone catalogue
Edit [devices.json](devices.json) to add model/codename entries. The Android app fetches the raw file at runtime from `https://raw.githubusercontent.com/RectifyData/ForgeROM/main/database/devices.json`, checks the schema, caches valid content, and uses Google's public supported-device catalogue as a fallback.

Entry format: `{"brand":"Samsung","marketing":"Galaxy S10+","codename":"beyond2lte","models":["SM-G975F"]}`.

Use official manufacturer or device-tree documentation to confirm exact codenames and SKUs. Do not put unverified unlock, root, or install claims in this database. Entries are **device-identification metadata**, not proof of flash compatibility. Keep `schema_version` at `1` until the parser is updated. 
