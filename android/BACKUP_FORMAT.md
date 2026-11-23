# Backup artifact format

The app exports progress and settings as one UTF-8 JSON file. The format is
independent of the application version and the puzzle generator version.

```json
{
  "format": "regex-crossword-backup",
  "schemaVersion": 1,
  "hash": {
    "algorithm": "sha256",
    "value": "<64 lowercase hex digits>"
  },
  "payload": {
    "progress": [
      {
        "puzzle": {
          "version": 6,
          "difficulty": "easy",
          "seed": 1000000
        },
        "grid": [
          {
            "r": 0,
            "c": 0,
            "value": "A"
          }
        ],
        "notes": [
          {
            "r": 0,
            "c": 1,
            "letters": [
              "B",
              "C"
            ]
          }
        ],
        "selected": null,
        "completed": false,
        "solution": null,
        "elapsedSeconds": 42
      }
    ],
    "lastPosition": null,
    "settings": {
      "keyboardLayout": "alphabetical"
    }
  }
}
```

`difficulty` is one of `easy`, `medium`, or `hard`. `keyboardLayout` is one of
`alphabetical`, `qwerty`, `qwertz`, `azerty`, `dvorak`, or `colemak`.

`schemaVersion` describes the payload structure and semantics. Puzzle
`version` is the generator version stored with each record. Records from other
generator versions are preserved but are not playable under the current
generator.

An unfinished record has `solution: null`. A completed record has a non-empty
answer array. A missing selection or last position is represented by `null`.
Only schema versions accepted by the app can be restored.

## Hash

SHA-256 covers a canonical encoding of `format`, `schemaVersion`, and
`payload`. The `hash` field itself is excluded. The canonical form:

- recursively sorts object keys by UTF-16 code unit order;
- preserves array order;
- writes no extra whitespace;
- escapes only quotes, backslashes, and U+0000 through U+001F;
- writes control escapes as lowercase `\u00xx`;
- writes integers without leading zeroes.

The resulting string is encoded as UTF-8 before hashing. Indentation, object
key order, and equivalent JSON string escapes therefore do not change the
digest, while payload changes and array reordering do.

The hash detects accidental or intentional changes to the file. It does not
authenticate who created the file.

## Validation and restore

Imports are limited to 32 MiB. The reader requires strict UTF-8 and strict
JSON, rejects duplicate object keys and unsupported non-integer numbers, and
validates the digest before attempting to interpret the payload. V1 then
checks exact field sets, integer ranges, puzzle identities, board coordinates,
letters, completed answers, duplicate puzzles, and duplicate cells or notes.

The whole artifact is rejected if any check fails. After validation, the app
shows a replacement summary. Confirmation replaces all progress, the last
position, and settings with one SharedPreferences commit. Cancelling or a
failed validation leaves storage untouched.

The artifact intentionally excludes generated puzzle caches. Puzzles are
regenerated on demand by the current generator when playable.
