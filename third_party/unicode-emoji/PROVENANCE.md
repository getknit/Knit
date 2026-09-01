# Provenance: Unicode `emoji-test.txt`

Vendored source for the generated emoji catalog shipped at `app/src/main/assets/emoji/emoji_en.tsv`
(the reaction picker's grid + search data).

- **Upstream:** <https://www.unicode.org/reports/tr51> (UTS #51, Unicode Emoji)
- **Pinned file:** <https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt>
- **Version:** Emoji 17.0 (file date 2025-08-04)
- **sha256:** `1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda`
- **Retrieved:** 2026-09-01
- **License:** Unicode License v3 (SPDX `Unicode-3.0`), © Unicode, Inc. — see [`LICENSE`](LICENSE) in this
  directory, copied verbatim from <https://www.unicode.org/license.txt>

`emoji-test.txt` and `LICENSE` are copied verbatim. They are **not** bundled in the APK — only the generated
`emoji_en.tsv` under `app/src/main/assets/emoji/` is packaged, so the app's no-`INTERNET`-permission design
is unaffected.

## Regenerate

```sh
python3 scripts/gen-emoji-catalog.py            # rewrite the asset
python3 scripts/gen-emoji-catalog.py --check    # CI: fail if the asset is stale
```

The generator reads `emoji-test.txt` from this directory and rewrites `app/src/main/assets/emoji/emoji_en.tsv`,
stamping the version, date and sha256 it reads off the file into the asset header (so the two cannot drift
silently). To move to a newer Unicode emoji release:

```sh
python3 scripts/gen-emoji-catalog.py --update 18.0   # downloads emoji-test.txt + license.txt here, regenerates
```

then update the version / date / sha256 / retrieved lines above and the version line in
`app/src/main/assets/emoji/README.md` and `THIRD-PARTY-NOTICES.md`. If Unicode adds a **group**, the generator
fails loudly — add it to `GROUPS` in the script and to `EmojiGroup` in the app in the same change. The exact
transform (fully-qualified only, Component group skipped, skin-tone flag) is documented in the script header.
