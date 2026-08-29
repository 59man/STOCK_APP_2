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

  // Bracket also used below to sanity-check Newton-Raphson: for cash flows spanning a very
  // short duration, f(r) is nearly flat over a huge range of r, and Newton can "converge" to
  // an astronomically large r that satisfies |f(r)| < tolerance by coincidence rather than
  // representing a meaningful annualized rate. A result outside this bracket is rejected and
  // falls through to the bounded bisection search below instead of being returned directly.
  const lo0 = -0.999
  const hi0 = 10

  // Newton-Raphson
  let r = 0.1
  for (let i = 0; i < 200; i++) {
    const fr = f(r)
    if (Math.abs(fr) < 1e-8) {
      if (r >= lo0 && r <= hi0) return r
      break
    }
    const dfr = df(r)
    if (dfr === 0) break
    const next = r - fr / dfr
    if (!isFinite(next) || next <= -1) break
    if (Math.abs(next - r) < 1e-10) {
      if (next >= lo0 && next <= hi0) return next
      break
    }
    r = next
  }

  // Bisection fallback over [-0.999, 10]
  let lo = lo0
  let hi = hi0
  if (Math.sign(f(lo)) === Math.sign(f(hi))) return null
  for (let i = 0; i < 200; i++) {
    const mid = (lo + hi) / 2
    if (Math.abs(hi - lo) < 1e-8) return mid
    Math.sign(f(mid)) === Math.sign(f(lo)) ? (lo = mid) : (hi = mid)
  }
  return (lo + hi) / 2
}
