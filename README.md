# Playtime

Playtime tracking with zero storage of its own — `/playtime [player]` shows a
player's total hours, rank and current session, `/tophours [page]` shows the
leaderboard of who played the most. For Paper/Purpur 1.21.x, Java 21.

## Why another playtime plugin?

Because the server already tracks it. The vanilla statistic
`minecraft:play_time` (ticks, 20/s — `Statistic.PLAY_ONE_MINUTE` in the Bukkit
API, a legacy name) is the source of truth:

- **Online players** are read live from memory (`player.getStatistic(...)`).
- **Offline players** come from `<mainWorld>/stats/<uuid>.json`, names from
  `usercache.json` (fallback: the server's offline-player profile cache).

So the plugin needs **no database** and starts already knowing the full
history of every player since the server began. Nothing to migrate, nothing to
lose, nothing to keep in sync — remove the plugin and no data is orphaned.

## Design

- `PlaytimeService` refreshes on a timer (`refresh-minutes`, default 5): a
  cheap **sync** pass snapshots online players' live ticks, then an **async**
  task scans the stats files + usercache, merges (live wins — the files lag
  until the server autosaves), sorts, and publishes one immutable snapshot
  through a single `volatile` reference.
- Commands and placeholders read **only the snapshot** — zero I/O on the
  command path, zero file I/O on the main thread anywhere.
- Every stats file is parsed inside its own try/catch: one corrupt JSON never
  kills the scan. Players whose name can't be resolved are skipped from the
  leaderboard (their ticks are still kept by UUID).
- On quit the leaderboard entry is patched from the live stat: the quit
  handler only enqueues values (O(1)) and one coalescing async task rebuilds
  the snapshot — a mass quit (restart, bots) never sorts the board on the
  main thread.
- Duplicate visible names (premium/offline pairs behind an auth plugin or
  Geyser) are handled: name lookups resolve to the best-ranked holder, while
  your own rank and placeholders always resolve by UUID.
- `onDisable` only cancels the timer — vanilla owns the data, there is
  nothing to flush.

On very large servers (tens of thousands of stats files) the scan is still
fully async, but you may want to raise `refresh-minutes` to reduce I/O churn.

## Commands

| Command | Description | Permission (default) |
|---|---|---|
| `/playtime` | Your own playtime, rank and current session | `playtime.use` (true) |
| `/playtime <player>` | Someone else's playtime (online or offline) | `playtime.use` (true) |
| `/playtime reload` | Reload config + language files, force a rescan | `playtime.admin` (op) |
| `/tophours [page]` (alias `/toph`) | Leaderboard, gold/silver/bronze podium, own rank in the footer; works from console/RCON | `playtime.top` (true) |

`playtime.admin` includes `playtime.use` and `playtime.top` as children.
Player-name arguments are validated against `^[a-zA-Z0-9_]{2,16}$` before any
lookup; out-of-range page numbers are clamped.

## Placeholders (PlaceholderAPI, optional softdepend)

- `%playtime_formatted%` — total playtime, e.g. `2d 4h 32m`
- `%playtime_hours%` — decimal hours in the server's default locale (`52.3` / `52,3`)
- `%playtime_rank%` — leaderboard position (`3`, or `—` if unranked)

All placeholders resolve from the in-memory snapshot: no I/O on the calling
thread, safe for per-tick scoreboards.

## Languages

Player-facing messages ship in **English** (`lang/en_US.yml`) and
**Portuguese** (`lang/pt_PT.yml`), MiniMessage format. Pick the server-wide
default with `locale` in `config.yml`; edit the files or drop extra
`lang/<locale>.yml` files to customize. With the optional PlayerSettings
plugin (same author) installed, each player sees the plugin in their own
language. Console logs stay in English.

## Config

```yaml
locale: pt_PT        # server-wide message language (see lang/)
refresh-minutes: 5   # leaderboard cache refresh interval, minimum 1
top:
  page-size: 10      # /tophours entries per page (5-25)
```

## Build

```bash
mvn -B -DskipTests package   # -> target/Playtime-1.0.0.jar
```

Requires Java 21. Built against paper-api 1.21.11.

## Source code

https://github.com/henriquescrrrr/carrageis-playtime

License: GPL-3.0
