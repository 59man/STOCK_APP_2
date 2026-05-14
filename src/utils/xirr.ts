interface CashFlow {
  date: Date
  amount: number
}

// XIRR: solve for annualised rate r where Σ CF_i / (1+r)^(days_i/365) = 0
// Uses Newton-Raphson with a bisection fallback.
export function xirr(cashflows: CashFlow[]): number | null {
  if (cashflows.length < 2) return null

  const t0 = cashflows[0].date.getTime()
  const years = cashflows.map((cf) => (cf.date.getTime() - t0) / (365.25 * 86_400_000))

  const f = (r: number) =>
    cashflows.reduce((sum, cf, i) => sum + cf.amount / Math.pow(1 + r, years[i]), 0)

  const df = (r: number) =>
    cashflows.reduce(
      (sum, cf, i) => sum - (years[i] * cf.amount) / Math.pow(1 + r, years[i] + 1),
      0
    )

  // Newton-Raphson
  let r = 0.1
  for (let i = 0; i < 200; i++) {
    const fr = f(r)
    if (Math.abs(fr) < 1e-8) return r
    const dfr = df(r)
    if (dfr === 0) break
    const next = r - fr / dfr
    if (!isFinite(next) || next <= -1) break
    if (Math.abs(next - r) < 1e-10) return next
    r = next
  }

  // Bisection fallback over [-0.999, 10]
  let lo = -0.999
  let hi = 10
  if (Math.sign(f(lo)) === Math.sign(f(hi))) return null
  for (let i = 0; i < 200; i++) {
    const mid = (lo + hi) / 2
    if (Math.abs(hi - lo) < 1e-8) return mid
    Math.sign(f(mid)) === Math.sign(f(lo)) ? (lo = mid) : (hi = mid)
  }
  return (lo + hi) / 2
}
