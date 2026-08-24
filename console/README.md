# `console/` — Presentation layer (L5)

**Status: ✅ complete — 8 pages in use**

React 18 + Vite operator console. Served by nginx in production, which also
proxies the two backends.

## Layout

Pages are grouped by the **backend layer they consume**, so the frontend tree
mirrors `ARCHITECTURE.md`:

```
src/
├── App.jsx                  shell: brand / tab / date / theme state
├── pages/
│   ├── analytics/           reads L3  — ZesStats, ZesSearch,
│   │                                    ZesPerformance, ZesCity
│   └── intelligence/        reads L4  — ZesPrediction, ZesPricing,
│                                        ZesOpportunity, ZesBacktest
├── components/              shared presentation (charts, TurkeyMap, DateCalendar)
│   └── ui/                  primitives
├── api/                     zesApi.js — the only fetch layer
├── lib/
│   ├── pricingModel.js      browser revenue engine (port of demand_model.py)
│   └── recommendations.js   recommendation_code → label/colour vocabulary
└── data/                    bundled Turkey provinces GeoJSON
```

## Commands

```bash
npm install
npm run dev      # :5173, proxies /zes to localhost:8080
npm run build    # → dist/
```

No linter and no test runner are configured; `npm run build` (Rollup) is the
only automated check, and it does catch broken imports and unresolved exports.

## Architecture notes

**No router, no state library.** `App.jsx` holds `brand`, `tab`, `date` and
`theme` in `useState` and renders the matching page; `date` is threaded to every
page as a prop. This is a deliberate scope choice for a single-operator internal
tool with no deep-linking requirement — revisit when sharing a link to a specific
station-date becomes a real need, since that is what a router would buy.

**All fetching goes through `api/zesApi.js`;** pages never call `fetch` directly.
Endpoint paths are relative, so the same build works behind the dev proxy and
behind nginx. That indirection is what makes ADR 0001 step 4 free on this side: when
the intelligence endpoints move from `core-api` to the Python service, nginx
re-routes by path prefix and this layer does not change at all.

**Dates come from the backend, not the calendar.** `DateCalendar` is driven by
`/…/available-dates`, which enumerates indices that actually exist. A user cannot
select a date that was never materialized.

## `lib/pricingModel.js` — the one place with real domain logic

This layer normally renders; here it *decides*. Dynamic pricing is
human-in-the-loop: the operator drags hour-bands and sets discounts, and revenue
is simulated live in the browser because a server round-trip per drag would make
the interaction unusable.

The file is a hand port of `pricing/demand_model.py`
and `elasticity.py` and **must stay numerically identical to them**. All
constants (base prices, η, `U_CAP`, discount tiers) are fetched from
`/zes/availability/pricing/config` rather than duplicated here, so the
assumptions stay server-authoritative and version-stamped. Only formulas are
ported.

Changing the demand model means changing `demand_model.py`, this file, and — for
a new parameter — the `config()` payload. Divergence is silent: the page keeps
rendering plausible revenue that no longer matches the documented model.

## Design conventions

Monochrome palette with a single accent, controls integrated into chart headers,
IBM Plex Sans / Mono. Theme is a `data-theme` attribute on `documentElement`;
tokens live in `index.css`. All user-facing copy is Turkish.
