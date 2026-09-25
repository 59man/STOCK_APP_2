# Bundled ticker logos

PNGs here are copied into app-private storage on first run (`LogoStore.seedFromAssets`)
and take priority over every remote logo source. The filename is the app's ticker plus
`.png`, matching `LogoStore.fileFor`.

Only tickers that **no** remote source covers belong here. Before adding one, check the
chain in `TickerLogo.kt`:

1. `financialmodelingprep.com/image-stock/<TICKER>.png` — the full symbol, suffix included
   (`EXUS.DE`, `KOMB.PR`); the bare base would name an unrelated US company
2. `icons.duckduckgo.com/ip3/<domain>.ico`
3. `www.google.com/s2/favicons?domain=<domain>&sz=128`

If any of those returns a real image, add the domain to `TICKER_LOGO_DOMAINS` instead of
adding a file here.

| File | Instrument | Source | Checked |
|---|---|---|---|
| `8306.T.png` | Mitsubishi UFJ Financial Group | `https://www.mufg.jp/apple-touch-icon.png` | 2026-09-19 |
| `8591.T.png` | ORIX Corporation | `https://www.orix.co.jp/ORIX_favicon_152x152.png` | 2026-09-19 |

Both predate querying the image API by full symbol, which now carries `8306.T` and `8591.T`
too (checked 2026-09-25); they stay as an offline-safe local copy.

Each is downscaled to 92x92 centred on a 128x128 white canvas, so the circular crop in
`TickerLogo` does not cut the wordmark.

These are trademarked company logos, used nominatively — to identify which holding a row is,
which is what a logo is for. This repository is public, so they are redistributed with it.
Keep the set to instruments actually held, prefer the company's own published asset, and drop
any logo whose owner objects.
