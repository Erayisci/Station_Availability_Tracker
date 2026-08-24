# `ingestion/` — Acquisition behind a provider contract (L1)

**Status: ✅ complete for ZES · ⚠️ contract has one implementation**

## Responsibility

Turn the charging network's private API into two uniform things: connector status
transitions persisted to the authoritative store, and canonical connector rows
that every layer above can read. This is the *only* layer allowed to know which
operator the data came from.

```
spi/                        the contract — three capabilities + canonical types
ProviderRegistry            resolves capabilities by providerId
providers/zes/              ✅ adapter: client, tracker, scheduler, mapper, nearby, landing index
api/                        manual triggers (/zes/poll-now, /zes/index-daily)
```

## Why the abstraction is here

Everything above this layer — roughly 5,000 lines of materialization, analytics
and intelligence — consumes only "a station had this connector in this state for
this interval." It never sees an auth scheme, a payload shape, or a provider's
status vocabulary. Pinning that boundary at layer 1 means supporting another
network is an adapter, not a fork of the pipeline.

The test for whether it holds: **adding a provider must not require an edit above
`ingestion/`.**

## The three capabilities

Split rather than merged, because they are separately optional.

| Interface | Answers | Optional? |
|---|---|---|
| [`StationStatusProvider`](spi/StationStatusProvider.java) | poll the network, persist transitions | no |
| [`StationPayloadMapper`](spi/StationPayloadMapper.java) | interpret a landed payload → `ConnectorRow`s | no |
| [`NearbySupplyProvider`](spi/NearbySupplyProvider.java) | how much competing capacity is within *r* km | **yes** |

Polling and mapping are separable because an archived network keeps its mapper for
backfill long after its API stops answering. Proximity queries are simply not
something every network offers — with no implementation registered, L2 degrades to
unmeasured supply fields rather than failing.

`StationStatusProvider`'s operations:

| Method | Meaning |
|---|---|
| `providerId()` | stable discriminator, e.g. `"zes"` — also the index prefix |
| `loadTodayOpenSlots()` | restore slots left open by a previous process lifetime |
| `pollAndTrack()` | poll once, persist transitions, return stations observed |
| `indexDailyToElastic(finalRun)` | materialise the day's snapshot; `finalRun` closes surviving open slots |

These signatures were **extracted** from the two trackers that already existed, not
designed up front — both had matched them exactly for months.

## Canonical types

`spi/` also owns the vocabulary the boundary speaks in, so that "provider-agnostic"
has a concrete referent rather than being a claim:

- **`ConnectorRow`** — one connector plus the day's windows, station attributes
  denormalised on. The platform's grain is the connector, because every metric
  above L1 is per-connector or an aggregate of them; station→EVSE→connector
  nesting is a ZES detail.
- **`AvailabilityWindow`** — one interval at one status. Timestamps stay strings,
  exactly as landed, so the indexed value equals the observed value by
  construction.
- **`ConnectorStatus`** — the three states the platform gives meaning to, with
  their wire codes. Deliberately not exhaustive: other provider states pass
  through untranslated because L3 aggregates statuses by name.
- **`NearbySupply`** — competing capacity, or the `-1` sentinel that means "not
  measured". Failure is a value here, not an exception: ~1,800 lookups run
  concurrently per build and individual timeouts must stay individual.

## Why a registry

`@Autowired StationPayloadMapper` works with one implementation and *looks*
abstract; with two it fails at startup, and the usual fix (`@Primary`, a
qualifier) quietly reintroduces the coupling this package exists to remove.
`ProviderRegistry` indexes implementations by `providerId` instead.

It resolves through `ObjectProvider`, not `List` injection, and that is
load-bearing rather than stylistic: eager list injection would construct every
tracker while the registry itself is under construction, and `ZesStatusTrackerService`
depends on L2's transform service, which depends back on the registry. That is a
real bean cycle, and Spring Boot rejects cycles by default — the context would
fail to start even though everything compiles. Deferred, per-capability lookup
means L2's `@PostConstruct` pulls in mappers alone (leaf beans) and trackers are
first resolved by a scheduler or request, after the context is built.

`soleProviderId()` lets the manual-trigger endpoints stay parameterless today and
**throws** the moment a second provider is registered, rather than picking one.
A repair endpoint has no basis for guessing whose data to rebuild.

### Honest status of the contract

ZES is currently the **only** implementation. A second network (ASTOR/Beefull) was
implemented and removed on 2026-08-17 as out of scope, and the EPDK register
adapter that was queued as a third was removed with it.

The contract was kept deliberately — a second network is considered likely, the
boundary costs almost nothing to hold, and re-deriving it later means re-untangling
provider details from five thousand lines. But it is an interface with one
implementor and no queued second, and it should be described that way: intent,
not a demonstrated abstraction.

What *did* change on 2026-08-17: L2 now genuinely honours the boundary. Before,
`ARCHITECTURE.md` Principle 4 was unverified in the strong sense that the layer
below the interface was clean while the layer above it read ZES field names
directly. Now the mapping lives here, and the claim is checkable — `grep -i zes`
in `materialization/` returns only the legacy URL prefix.

Still coupled, and worth naming instead of implying otherwise:

- **L3, L4, L5 compare status wire strings directly** — `analytics/` string
  literals, `intelligence/config.py`'s `CHARGING_STATUS`, three console charts
  using `"Charging"` as a Recharts `dataKey`. `ConnectorStatus` documents those
  codes as a contract but does not stop anyone bypassing it.
- **Every read endpoint is mounted under `/zes/`**, because the console's API
  layer hard-codes the prefix.
- **The landing index mapping is ZES-shaped** — correctly so, and correctly
  located here, but it means a second network needs its own landing writer.
- **`analytics/enrichment/ZesStationEnrichmentService` reads the landing index
  directly**, pulling `id` / `latitude` / `longitude` — ZES's camelCase names — to
  geocode stations for the Overpass lookup. It is the one reader above L1 that
  still touches a raw payload. Left alone deliberately: the canonical stage has
  the same coordinates as `lat` / `lng`, but it is one document *per connector*
  rather than per station, so swapping the source is not a rename, and the
  environment index's own field names are consumed downstream.

If a second network never materialises, collapsing `spi/` and `providers/` into a
flat `ingestion/` is the correct simplification, and it is a small change.

### Invariants an adapter must uphold

1. **H2 before Elasticsearch.** Persist transitions relationally first; derived
   stores must stay rebuildable.
2. **Close each slot exactly once.** Open slots span polls, so the end-of-day call
   must not double-count when re-run.
3. **Idempotent per date.** Re-running a day rebuilds that day only.
4. **Contain failure.** A provider outage fails its own poll and nothing else.
5. **Mappers are pure.** No I/O, no clock, no shared state — they are called while
   scrolling a day's snapshot and again during backfill, and must return the same
   rows both times.

## Scheduling

Cadence is externalised to `application.properties`, tunable without a rebuild:

```properties
zes.poll.intervalMs=300000            # 5 min
zes.daily.cron=0 59 23 * * *          # finalize
zes.performance.autoBuildCron=0 15 0 * * *
```

`ZesScheduler` also triggers the L2 performance build at 00:15 for the *previous*
day, skipping when the source stage is missing or the target already exists. That
cross-layer call is deliberate — cadence is an ingestion property — but it is the
one place a layer reaches downward, and worth knowing about when reading L2. It
now passes `providerId()` explicitly, so a second network gets its own scheduler
rather than a shared one whose failure would stall both.
