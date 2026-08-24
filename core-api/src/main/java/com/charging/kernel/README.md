# `kernel/` — Shared kernel (L0)

**Status: ✅ complete**

## Responsibility

Infrastructure that every layer depends on and that depends on no layer. If a
type here imported from `ingestion/`, `analytics/` or `intelligence/`, the
dependency graph would cycle — that is the rule this package exists to enforce.

| Package | Contents |
|---|---|
| `es/` | `EsConfig` (connection, env-driven), `IndexCatalog` (the naming contract) |
| `persistence/` | JPA entities + repositories for authoritative connector history |
| `admin/` | Index health, inspection and deletion endpoints (`/zes/admin/*`) |

Named `kernel` rather than `platform` so the Java and Python sides match:
`platform` is a Python standard-library module, and a top-level package with
that name would shadow it (`pandas` and `matplotlib` both `import platform`).

## `es/IndexCatalog` — the contract

The one file that describes the whole pipeline. Every dataset is
`{prefix}-{YYYY-MM-DD}`, each stage annotated with the runtime that **writes**
it. Note that [ADR 0001](../../../../../../../docs/adr/0001-intelligence-storage.md)
moves the five L4 entries out of Elasticsearch onto Parquet — the catalog remains
the contract, but it will describe two stores rather than one. Its Python mirror is [`intelligence/kernel/index_catalog.py`](../../../../../../../intelligence/kernel/index_catalog.py)
and the two must agree literally — an index name is the only thing the two
runtimes share.

Before this file existed, prefixes were private string constants in 15 Java
services and 9 Python modules; no single place described the chain and a rename
could not be performed safely in either runtime.

**When adding a stage:** declare it here first, in both runtimes, with its owner.

## `persistence/` — authoritative state

`ConnectorStatusHistory` is the platform's only authoritative record; every
derived dataset is rebuildable from it. It lives in the kernel rather than under
the provider because the retention sweep, the open-slot recovery on startup, and
`ZesConnectorTransformAnalysisService` all read it across layer boundaries.

Retention is bounded (`zes.history.keepDays`, default 7). That bound is what
makes an embedded H2 defensible as the system of record — see
`ARCHITECTURE.md § Technology Stack Rationale`.

## `admin/` — operating the chain

`ZesAdminController` + `ZesPerformanceHealthService` answer "which dates exist,
which are broken, which are missing" and can delete a bad date so it can be
rebuilt. This is kernel rather than analytics because it inspects *the store
itself* rather than the domain.

## Contracts to uphold

- No imports from any numbered layer. Kernel is a leaf.
- Every ES-touching service builds its client from `EsConfig.host()` — never a
  hardcoded host — so one environment variable relocates the cluster.
- Index names come from `IndexCatalog`, never a literal.
