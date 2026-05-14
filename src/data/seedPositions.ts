import { Position } from '../types'

// All buy transactions extracted from Fio banka statements 2022–2026.
// FIO GLOBAL FOND CZK is consolidated to weighted average of 17 DCA purchases
// (6910 units, total cost 10 990.10 CZK → avg 1.5904 CZK/unit).
export const SEED_POSITIONS: Omit<Position, 'id'>[] = [
  // ── COLT CZ (Česká zbrojovka / ColtCZ) ───────────────────────────────
  { ticker: 'COLT.PR', name: 'Colt CZ Group SE', type: 'stock', quantity: 2,  buyPrice: 614.00,  buyDate: '2022-03-25', currency: 'CZK' },
  { ticker: 'COLT.PR', name: 'Colt CZ Group SE', type: 'stock', quantity: 2,  buyPrice: 616.00,  buyDate: '2022-03-28', currency: 'CZK' },
  { ticker: 'COLT.PR', name: 'Colt CZ Group SE', type: 'stock', quantity: 30, buyPrice: 705.00,  buyDate: '2025-02-03', currency: 'CZK' },
  { ticker: 'COLT.PR', name: 'Colt CZ Group SE', type: 'stock', quantity: 13, buyPrice: 732.00,  buyDate: '2025-09-01', currency: 'CZK' },

  // ── MONETA MONEY BANK ────────────────────────────────────────────────
  { ticker: 'MONET.PR', name: 'Moneta Money Bank', type: 'stock', quantity: 2,  buyPrice: 89.80,  buyDate: '2022-03-28', currency: 'CZK' },
  { ticker: 'MONET.PR', name: 'Moneta Money Bank', type: 'stock', quantity: 60, buyPrice: 136.20, buyDate: '2025-02-03', currency: 'CZK' },

  // ── PHILIP MORRIS ČR ─────────────────────────────────────────────────
  { ticker: 'TABAK.PR', name: 'Philip Morris ČR', type: 'stock', quantity: 1, buyPrice: 16900.00, buyDate: '2022-11-14', currency: 'CZK' },
  { ticker: 'TABAK.PR', name: 'Philip Morris ČR', type: 'stock', quantity: 1, buyPrice: 17180.00, buyDate: '2025-02-03', currency: 'CZK' },
  { ticker: 'TABAK.PR', name: 'Philip Morris ČR', type: 'stock', quantity: 1, buyPrice: 17180.00, buyDate: '2025-02-03', currency: 'CZK' },
  { ticker: 'TABAK.PR', name: 'Philip Morris ČR', type: 'stock', quantity: 1, buyPrice: 18000.00, buyDate: '2025-09-01', currency: 'CZK' },
  { ticker: 'TABAK.PR', name: 'Philip Morris ČR', type: 'stock', quantity: 1, buyPrice: 18000.00, buyDate: '2025-09-01', currency: 'CZK' },

  // ── VIG (Vienna Insurance Group) ─────────────────────────────────────
  { ticker: 'VIG.PR', name: 'VIG', type: 'stock', quantity: 15, buyPrice: 1092.00, buyDate: '2025-09-01', currency: 'CZK' },

  // ── CSG ───────────────────────────────────────────────────────────────
  { ticker: 'CSG.PR', name: 'CSG', type: 'stock', quantity: 30, buyPrice: 755.00, buyDate: '2026-01-23', currency: 'CZK' },
  { ticker: 'CSG.PR', name: 'CSG', type: 'stock', quantity: 20, buyPrice: 755.00, buyDate: '2026-01-23', currency: 'CZK' },

  // ── FIO GLOBAL FOND CZK (DCA, weighted avg of 17 purchases) ──────────
  { ticker: 'FIOG.PR', name: 'Fio Global Fond CZK', type: 'fund', quantity: 6910, buyPrice: 1.5904, buyDate: '2022-03-28', currency: 'CZK' },

  // ── GOLD (Revolut XAU) ────────────────────────────────────────────────
  // Jul 18, 2025: 10,815 CZK → 0.148937 XAU (net after Revolut fee of 0.001489 XAU)
  // Effective buy price: 10,815 / 0.148937 = 72,614.60 CZK/troy oz
  { ticker: 'XAU', name: 'Gold (Revolut)', type: 'commodity', quantity: 0.148937, buyPrice: 72614.60, buyDate: '2025-07-18', currency: 'CZK' },

  // ── XTB — KOMERČNÍ BANKA (XTB ticker: KOMB.CZ → Yahoo: KOMB.PR) ──────
  { ticker: 'KOMB.PR', name: 'Komerční banka', type: 'stock', quantity: 10, buyPrice: 1102.00, buyDate: '2026-04-07', currency: 'CZK' },

  // ── XTB — XETRA-GOLD (4GLD.DE, EUR-denominated, buy prices stored in CZK) ──
  // CZK buy price = total CZK debited / shares bought for each purchase date
  { ticker: '4GLD.DE', name: 'Xetra-Gold', type: 'commodity', quantity: 7.5857, buyPrice: 2583.78, buyDate: '2025-10-03', currency: 'CZK' },
  { ticker: '4GLD.DE', name: 'Xetra-Gold', type: 'commodity', quantity: 1.9467, buyPrice: 2718.48, buyDate: '2025-10-29', currency: 'CZK' },
  { ticker: '4GLD.DE', name: 'Xetra-Gold', type: 'commodity', quantity: 1.9850, buyPrice: 2720.33, buyDate: '2025-11-05', currency: 'CZK' },
  { ticker: '4GLD.DE', name: 'Xetra-Gold', type: 'commodity', quantity: 7.6003, buyPrice: 3289.24, buyDate: '2026-01-23', currency: 'CZK' },
  { ticker: '4GLD.DE', name: 'Xetra-Gold', type: 'commodity', quantity: 1.5652, buyPrice: 3135.47, buyDate: '2026-02-02', currency: 'CZK' },
  { ticker: '4GLD.DE', name: 'Xetra-Gold', type: 'commodity', quantity: 3.0970, buyPrice: 3194.85, buyDate: '2026-03-20', currency: 'CZK' },
  { ticker: '4GLD.DE', name: 'Xetra-Gold', type: 'commodity', quantity: 0.6502, buyPrice: 3075.67, buyDate: '2026-05-04', currency: 'CZK' },

  // ── XTB — iSHARES MSCI WORLD EX USA (EXUS.DE, EUR-denominated, buy price in CZK) ──
  // 3 + 0.4281 shares on 2026-05-04; combined: 3.4281 shares, 3125.48 CZK total
  { ticker: 'EXUS.DE', name: 'iShares MSCI World ex USA', type: 'etf', quantity: 3.4281, buyPrice: 911.72, buyDate: '2026-05-04', currency: 'CZK' },

  // ── UNICREDIT onemarkets — BlackRock Global Equity Dynamic Opport. Fund ACC HCZK ──
  // ISIN: LU2606422355. Pre-existing lot consolidates purchases before visible history
  // (53.219 units, back-calculated avg price = total_cost − visible_lots_cost / pre-existing_qty).
  // 6 monthly ~1 000 CZK DCA purchases are from the 14.5.2025–14.5.2026 report.
  { ticker: 'LU2606422355', name: 'OM BlackRock Global Equity Dyn.', type: 'fund', quantity: 53.219, buyPrice: 131.56, buyDate: '2024-10-01', currency: 'CZK' },
  { ticker: 'LU2606422355', name: 'OM BlackRock Global Equity Dyn.', type: 'fund', quantity: 7.728,  buyPrice: 129.41, buyDate: '2025-05-21', currency: 'CZK' },
  { ticker: 'LU2606422355', name: 'OM BlackRock Global Equity Dyn.', type: 'fund', quantity: 7.879,  buyPrice: 126.92, buyDate: '2025-06-20', currency: 'CZK' },
  { ticker: 'LU2606422355', name: 'OM BlackRock Global Equity Dyn.', type: 'fund', quantity: 7.607,  buyPrice: 131.45, buyDate: '2025-07-21', currency: 'CZK' },
  { ticker: 'LU2606422355', name: 'OM BlackRock Global Equity Dyn.', type: 'fund', quantity: 7.568,  buyPrice: 132.14, buyDate: '2025-08-20', currency: 'CZK' },
  { ticker: 'LU2606422355', name: 'OM BlackRock Global Equity Dyn.', type: 'fund', quantity: 7.503,  buyPrice: 133.28, buyDate: '2025-09-19', currency: 'CZK' },
  { ticker: 'LU2606422355', name: 'OM BlackRock Global Equity Dyn.', type: 'fund', quantity: 7.246,  buyPrice: 138.02, buyDate: '2025-10-21', currency: 'CZK' },

  // ── UNICREDIT onemarkets — Fidelity World Equity Income Fund ACC HCZK ──
  // ISIN: LU2606421548.
  { ticker: 'LU2606421548', name: 'OM Fidelity World Equity Income', type: 'fund', quantity: 54.178, buyPrice: 129.21, buyDate: '2024-10-01', currency: 'CZK' },
  { ticker: 'LU2606421548', name: 'OM Fidelity World Equity Income', type: 'fund', quantity: 7.406,  buyPrice: 135.03, buyDate: '2025-05-21', currency: 'CZK' },
  { ticker: 'LU2606421548', name: 'OM Fidelity World Equity Income', type: 'fund', quantity: 7.662,  buyPrice: 130.51, buyDate: '2025-06-19', currency: 'CZK' },
  { ticker: 'LU2606421548', name: 'OM Fidelity World Equity Income', type: 'fund', quantity: 7.627,  buyPrice: 131.12, buyDate: '2025-07-21', currency: 'CZK' },
  { ticker: 'LU2606421548', name: 'OM Fidelity World Equity Income', type: 'fund', quantity: 7.622,  buyPrice: 131.21, buyDate: '2025-08-20', currency: 'CZK' },
  { ticker: 'LU2606421548', name: 'OM Fidelity World Equity Income', type: 'fund', quantity: 7.742,  buyPrice: 129.17, buyDate: '2025-09-19', currency: 'CZK' },
  { ticker: 'LU2606421548', name: 'OM Fidelity World Equity Income', type: 'fund', quantity: 7.643,  buyPrice: 130.84, buyDate: '2025-10-21', currency: 'CZK' },

  // ── UNICREDIT onemarkets — Pictet Global Opportunities Allocation Fund ACC HCZK ──
  // ISIN: LU2595011649.
  { ticker: 'LU2595011649', name: 'OM Pictet Global Opport. Alloc.', type: 'fund', quantity: 57.242, buyPrice: 122.29, buyDate: '2024-10-01', currency: 'CZK' },
  { ticker: 'LU2595011649', name: 'OM Pictet Global Opport. Alloc.', type: 'fund', quantity: 8.248,  buyPrice: 121.25, buyDate: '2025-05-21', currency: 'CZK' },
  { ticker: 'LU2595011649', name: 'OM Pictet Global Opport. Alloc.', type: 'fund', quantity: 8.379,  buyPrice: 119.34, buyDate: '2025-06-20', currency: 'CZK' },
  { ticker: 'LU2595011649', name: 'OM Pictet Global Opport. Alloc.', type: 'fund', quantity: 8.242,  buyPrice: 121.33, buyDate: '2025-07-21', currency: 'CZK' },
  { ticker: 'LU2595011649', name: 'OM Pictet Global Opport. Alloc.', type: 'fund', quantity: 8.195,  buyPrice: 122.02, buyDate: '2025-08-20', currency: 'CZK' },
  { ticker: 'LU2595011649', name: 'OM Pictet Global Opport. Alloc.', type: 'fund', quantity: 8.221,  buyPrice: 121.64, buyDate: '2025-09-19', currency: 'CZK' },
  { ticker: 'LU2595011649', name: 'OM Pictet Global Opport. Alloc.', type: 'fund', quantity: 8.023,  buyPrice: 124.64, buyDate: '2025-10-21', currency: 'CZK' },
]
