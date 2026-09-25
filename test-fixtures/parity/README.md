# Web ↔ Android parity fixture

`input.json` is one frozen portfolio snapshot (positions, quotes, FX rates, midnight anchors,
dividends, tax overrides) chosen to hit the awkward cases: partial and full sells, a lot sold
at price 0, a zero-quantity lot, a pence-quoted London line, a JPY quote against EUR lots, a
manually priced fund, crypto.

`expected.json` is the golden both apps must reproduce to 0.01:

- web: `src/utils/parity.test.ts` — generates it (`npx vitest run -u parity`) and asserts it
- Android: `core/calc/src/test/.../ParityTest.kt` — asserts it

After regenerating, read the diff by hand before committing: the golden is only as right as
the web code that wrote it.

Figures covered: per row (avg buy, price, value, cost basis, price P&L, net dividends, total
return, IRR, today's change + % + method, closed), per portfolio (value, today's change + %,
total return, IRR), and the Value / Today / Total return sort orders in CZK, USD and EUR.

## Intentional differences

None at present.

## Findings (bug hunt 2026-09-25)

| # | Symptom | Cause | Status |
|---|---|---|---|
| F1 | A lot sold at price 0 counted as open *and* sold | open test used `!sellPrice` | fixed, both |
| F2 | Zero-quantity lot → NaN average buy price | unguarded division | fixed, both |
| F3 | One closed total-loss row blanked the portfolio IRR | IRR gate counted closed rows | fixed, both |
| F4 | Android today's change −3,113 vs web +369 | summary summed legacy prev-close `dailyChange` | fixed |
| F5 | Android Today sort disagreed with displayed values | sorted on legacy `dailyChange` | fixed |
| F6 | One 429 / offline start pinned today's change to the fallback until midnight (Android: across restarts) | transient anchor failures cached as null | fixed, both — only 404 cached |
| F7 | XLSX import crashes on Android ≤ 13 | `Stream.toList()` is API 34 | fixed |
| F8 | New Flow per recomposition in `MainActivity` | operator inside composition | fixed |
| F9 | Android: one failed FX refresh reset that pair to the hardcoded default (USD 25 vs ~23) | rebuilt from `DEFAULT_RATES` | fixed — keeps last live rate |
| F10 | London holding showed today's change −99 % | GBp anchor not scaled to GBP | fixed, both |
| — | Dividend / FX / sync failures swallowed without trace | empty catch blocks | now logged |
