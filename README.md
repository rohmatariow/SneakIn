# SneakIn

*by [@rohmatariow](https://github.com/rohmatariow)*

**SneakIn** is a Burp Suite extension that detects **improper input validation** through
**differential analysis**, one parameter at a time.

It is the inverse of a normal diff tool. Every payload SneakIn sends is *illegal input that the
server should reject*. If the server instead responds as if the request **succeeded**, that
parameter is flagged **`VULNERABLE`** an illegal value *sneaked through* validation.

> The name reflects the core signal: a payload that should have been rejected but slips in
> unnoticed.

[![Burp Suite](https://img.shields.io/badge/Burp%20Suite-Professional-orange)](https://portswigger.net/burp)

---

## How it works

The workflow is user-driven:

1. **Right-click** a request in Proxy history, Repeater, or the Target/Site map →
   **Send to SneakIn**. This opens a new, independent scan tab numbered Repeater-style (`1`, `2`,
   `3`, …). **Double-click the tab title to rename it** (the host stays as the tooltip).
2. SneakIn enumerates every **injection point** in the request (see below) and lists them in
   **Targets** (all selected by default; **Shift+click** a checkbox to select/deselect a whole
   range at once). Use the **Search** box to filter the list by parameter name — handy when a
   request has dozens of fields and only a few must be marked Unique. Tick **Unique** on any field
   the server requires to be unique per request see *Unique parameters* below.
3. Click **Start**. The engine:
   - sends the original request **N times** to learn a **baseline** and a **noise map** (fields
     that vary naturally — timestamps, request IDs, CSRF tokens);
   - then mutates **one parameter at a time** (Intruder "sniper" style), holding all others at
     their baseline value, with deliberately-illegal payloads;
   - classifies each response and **stops testing a parameter on its first `VULNERABLE` hit**
     (early-exit: the goal is to flag *which* parameters are broken, not to count payloads).

### Injection points covered

SneakIn targets **parameters only**:

- **Query** string parameters
- **JSON** body — every nested leaf path at **any depth**, including dicts inside arrays inside
  dicts (`user.address.zip`, `items[0].id`, `accountList[0].merchantDtls.field1`). A `null` leaf is
  a valid target: if the server accepts an illegal value where it previously had none, that is a
  finding, and it is filled into the Combine request.
- **Wrapped JSON** (auto-detected, **recursive**) — when a value is *itself* a JSON document,
  SneakIn descends into its inner leaves instead of testing the whole blob as one opaque value:
  - a **query/form parameter** whose value is JSON (`post={"post":{"id":1,"title":"x"}}`), whether
    the value is raw or url-encoded — shown as `post › post.id`, `post › post.title`;
  - a **JSON string that contains JSON** (`{"data":"{\"id\":1}"}`) — shown as `data › id`.
  Wrapping nests to any depth — a param holding JSON holding a JSON-string holding JSON enumerates as
  `post › data › id`. Mutation re-embeds the change through the whole chain (url-encoding it back for
  params, re-escaping each JSON string), so only the targeted leaf changes and every other byte is
  preserved.
- **Form-encoded** body fields (`application/x-www-form-urlencoded`)
- **Multipart** (`multipart/form-data`):
  - regular **field values** (`MULTIPART_FIELD`)
  - file-part **filenames** (`MULTIPART_FILENAME`) — the binary file content is never mutated

> **Headers, cookies and path segments are intentionally out of scope** (they are not parameters).
> XML bodies are also out of scope for v1 (logged and skipped).

### Payload categories

| Category       | Applies to                         | Examples                                  |
|----------------|------------------------------------|-------------------------------------------|
| **Symbol**     | every field                        | `<{!@#}>`, `<>`, `\|`                       |
| **Bad Format** | fields with a detected format      | `2026-06-12zzz` (append), `2026-13-45` (malform), `2026-06` (truncate) |
| **Negative**   | numeric fields only                | `-1`, `-99999`                            |

Formats detected for **Bad Format**: date, email, UUID, numeric. All three groups are editable
from the **Config → Payloads** editor (add / edit / delete / enable-disable, plus reset-to-default
per group). Disabled payloads consume no requests.

### Verdicts

| Verdict      | Colour | Meaning                                                         |
|--------------|--------|-----------------------------------------------------------------|
| `VULNERABLE` | red    | Illegal input was accepted. **This is the finding.**            |
| `REVIEW`     | amber  | Diverged but ambiguous — manual check needed.                   |
| `REJECTED`   | grey   | Input was not accepted — validation works. The good case.       |

`VULNERABLE` carries a sub-label by category: *Symbol Accepted*, *Bad Format Accepted*,
*Negative Accepted*. There is **no** error verdict — a `5xx`/stack trace is **not** a finding (it
never means the input was accepted); it is treated as `REJECTED` (the server errored rather than
accepting the value).

---

## Build

Requirements: JDK 17+ (the build provisions a Java 21 toolchain automatically).

```bash
gradle shadowJar
```

The loadable fat jar is written to **`build/libs/sneakin.jar`** (Kotlin runtime + Gson bundled;
the Montoya API is provided by Burp at runtime).

## Load in Burp

**Extensions → Installed → Add → Extension type: Java → Select file → `build/libs/sneakin.jar`.**
A **SneakIn** suite tab appears.

---

## Session / authentication handling (important)

SneakIn v1 does **not** extract or refresh tokens itself. Instead it sends every request **through
Burp** (`api.http().sendRequest`), so requests are subject to the **Session Handling Rules** you
configure in Burp:

**Settings → Sessions → Session Handling Rules.**

For authenticated targets, set up (one-time):

1. A rule whose action is **"Use cookies from the Burp cookie jar"** (so the session cookie is
   attached).
2. If the target uses single-use CSRF tokens, a **macro** that refreshes the token, applied via a
   session-handling rule.
3. **Crucially, the rule's scope must include "Extensions"** (URL scope → Tools scope → tick
   **Extensions**). Otherwise SneakIn's requests bypass the rule and you will see spurious
   `REJECTED`/`REVIEW` results from failed auth.

If the seed host is out of Burp's target scope, SneakIn logs a warning but still runs.

---

## Unique (must-vary) parameters

Some endpoints reject a request when a field repeats a value the server already holds — a
registration `email`, an `accountNo`, a `referenceId`, a `nickname`. Because SneakIn replays the
same baseline value on the baseline samples **and** on every mutation that holds a field constant,
such a server would reject every repeat — so the scan would see spurious `REJECTED`/`REVIEW`
results that have nothing to do with input validation, and never finish meaningfully.

Tick **Unique** on those fields in **Targets**. SneakIn then rotates each flagged field to a fresh
value on **every** request (baseline + every mutation), weaving an incrementing counter into the
value while keeping its shape:

| Field type | Original | Rotated example |
|------------|----------|-----------------|
| numeric    | `123`            | `123<n>`            |
| email      | `a@b.com`        | `a<n>@b.com`       |
| uuid       | `b457…cb499`     | same shape, trailing node replaced |
| other      | `nickname`       | `nickname-<n>`     |

The counter is monotonic and time-seeded, so values stay unique within a run and rarely collide
across runs. When a flagged field is itself being tested, it receives the **payload** for that
request (not a rotation) — its uniqueness rotation applies on every *other* request. Flagging a
field **Unique** is independent of selecting it for **Test**: a field can rotate without being
tested, and vice-versa.

> Note: this creates a fresh server-side record per request (that is the point — it lets the scan
> run to completion). The **Combine** tab does not auto-rotate; edit unique values there before
> re-sending if needed.

## Configuration (Config → Settings)

- **Definition of success:** status (any 2xx, or an explicit list), positive-match (must be
  present), negative-match (must be absent), length/timing tolerance, lock content-type — each
  with an on/off toggle and weight.
- **Comparison mode:** `auto` (structural for JSON, similarity otherwise), `structural`, `similarity`.
- **Manual ignore rules:** extra force-masked paths/regex on top of the auto noise map.
- **Baseline:** sample count N (default 3), warm-up.
- **Engine:** thread count, inter-request delay, timeout, retries, max-requests cap
  (0 = unlimited), `429` backoff.
- **Preset:** Strict / Balanced / Sensitive (shifts how close a response must be to baseline to
  count as "accepted").

## Results

The **Results** table streams findings live (`REJECTED` rows hidden by default — toggle
**Show rejected**). It is sortable/filterable. Row click shows **baseline vs mutated**
request/response side-by-side with the classification reasons.

- **Bulk copy** (multi-select): right-click → *Copy parameters* / *Copy as lines* / *Copy as CSV*.
- **Copy all vulnerable params** and **Select all vulnerable** buttons in the header.
- **Send to Repeater** / **Send to Comparer** from the right-click menu.
- **Export CSV** / **Export JSON** (the JSON export includes baseline + mutated request/response
  and the classification reasons).

## Combine

The **Combine** tab takes the `VULNERABLE` findings (the parameters whose illegal payload *sneaked
in*) and lets you fill them all into **one request** so you can see — and re-send — every accepted
value at once.

- **Per category:** the **Category** filter (All / Symbol / Bad Format / Negative) lists the
  accepted parameters of one category; **Copy parameters** copies that category's parameter names.
- **Combined request:** every checked finding's successful payload is chained onto the original
  request and shown live in the editor — so you see the full request with all accepted values
  populated for that category.
- **Send as one:** uncheck rows to narrow to a hand-picked subset, then **Send to Repeater** or
  **Send now** (fires the combined request and shows the response inline). The combined request
  editor is editable, so manual tweaks before sending are respected.

> The tab refreshes from the latest results when you open it and when a scan finishes; **Refresh**
> re-pulls on demand. Because the scan early-exits a parameter on its first `VULNERABLE`, each
> parameter appears at most once, so the combined request never has conflicting mutations.
