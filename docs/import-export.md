# Import and export

On the main screen, tap **Export apps**, confirm, and choose where to save
`mws-apps.json` using the Android file picker. To import, tap **Import apps**,
choose a file, and confirm the number of apps to add. Canceling either picker
or confirmation leaves the saved apps unchanged.

Import appends all entries in file order as new apps. Existing apps are kept;
importing the same file twice creates two sets of apps. The entire import is
committed together, so an invalid entry or failed database write adds no apps.
Each imported app gets a fresh ID, timestamps and empty isolated site data.
Cached icons, cookies, login sessions, site storage, credentials, profile state
and sensitive URL consent records are excluded. Manifest metadata and colors
are preserved; imported apps initially use the default icon.

Saved URLs may contain secrets. Export warns before writing them to a file;
import confirmation also confirms saving these URLs and including them in device
backups. Only import files you trust and keep exported files private.

## Version 1 format

The file is a UTF-8 JSON object, limited to 8 MiB. Unknown fields are ignored.
`format`, `version`, and `apps` are required. Only version 1 is supported.
The apps array order is the display order; at most 1,000 apps may be imported. Each entry has these fields:

```json
{
  "format": "mobile-web-shell",
  "version": 1,
  "apps": [
    {
      "name": "Example",
      "nameSource": "USER_EDITED",
      "configuredUrl": "https://example.com/",
      "finalUrl": null,
      "launchUrl": "https://example.com/",
      "scope": "https://example.com/",
      "allowThirdPartyCookies": false,
      "manifestSnapshot": null,
      "themeColor": null,
      "backgroundColor": null
    }
  ]
}
```

`name` follows editor validation: a visible name of at most 128 Unicode code
points, without control characters; surrounding whitespace is trimmed. `nameSource` is `USER_EDITED` or `DISCOVERED`.
`configuredUrl`, `launchUrl`, and `scope` are required absolute HTTPS strings,
validated and normalized by the same URL policy used by the shell. The launch
URL must be within scope (same origin and scope path prefix). `finalUrl` is an
HTTPS string or null. Configured, final and launch URLs must have the same origin. `allowThirdPartyCookies` is a required JSON boolean.
`manifestSnapshot` is a string containing valid serialized manifest metadata
(including a canonical HTTPS `manifestUrl`), limited to 1 MiB, or null.
`themeColor` and `backgroundColor` are signed 32-bit Android ARGB integers or null.
Nullable fields may be omitted. All other entry fields are required. Empty
`apps` arrays are valid.
