# <img src="https://silentclient.net/assets/logo.png" alt="Silent Client" width="32" height="32" style="vertical-align: middle;"> SCSO — Silent Client Staff Only

A Folia plugin that keeps a server staff-only by asking Silent Client who a player is
**before they are allowed in**, then showing that player's Silent Client rank — with its
official icon and colour — in chat and the tab list. Non-staff can be let in by name with
a whitelist command, and the plugin hooks into **TAB (NEZNAMY)** when it is installed.

Built and verified against **Paper/Folia API `26.1.2.build.74-stable`** (Java 25), and
boot-tested on a real Folia `26.1.2` server with TAB 6.2.0 installed.

The Java package is still `net.zouhmi.silentstaffmembers` and the project folder is still
`SilentStaffMembers/`; only the plugin itself was renamed.

## What it does

1. `AsyncPlayerPreLoginEvent` fires while the connection is still logging in. The plugin
   looks the username up on Silent Client and waits (bounded) for the answer.
2. If the account holds **no** staff rank and is not whitelisted, the login is denied with
   the configured kick message. Non-staff never reach the world.
3. If the account holds a staff rank, the login is allowed and the rank is remembered for
   that session.
4. On join the rank is written as the player's display name, tab-list name and chat
   prefix — colored with the hex color the service sends with the rank, and prefixed with
   the rank's icon where the client can render it — and pushed through TAB's API as well
   when TAB owns the tab list.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/scso reload` | `scso.reload` | Re-reads the config, rebuilds the icon pack, re-detects TAB and re-verifies everyone online |
| `/scso pack` | `scso.reload` | Prints the icon pack's file, SHA-1, configured URL, glyphs and how many clients loaded it |
| `/scso whitelist add <player>` | `scso.whitelist` | Lets a non-staff player join |
| `/scso whitelist remove <player>` | `scso.whitelist` | Takes that access away (kicking them if they are online and have no rank) |
| `/scso whitelist list` | `scso.whitelist` | Lists whitelisted names |

`/staff` and `/silentstaff` are aliases of `/scso`, and the whitelist also answers to
`/scso wl`. `scso.admin` (default: op) is the parent of both sub-permissions.

## Rank check

Two anonymous, read-only Silent Client endpoints are used, both discovered from the
endpoints already used elsewhere in this repository (`probe_profile.py`, `coins_daemon.py`):

| Step | Request | Gives |
| --- | --- | --- |
| 1 | `GET https://api.silentclient.net/users/account/{username}` | `account.user_id`, `account.username` |
| 2 | `GET https://api.silentclient.net/ranks/ranks/user/{user_id}` | `ranks[]` with `name`, `key`, `priority`, `meta.color` |

The returned `account.username` must match the joining name (case-insensitively), so a
lookup that resolves to a different account is treated as "not staff" rather than trusted.

Staff ranks (`config.yml`, case-insensitive, `_`/`-`/space-insensitive):

`Tester`, `Senior Tester`, `Tech Support`, `Senior Tech Support`, `Client Mod`,
`Senior Client Mod`, `Tester Manager`, `Client Mod Manager`, `Admin`, `Senior Admin`,
`Developer`, `Owner`

When an account holds several, the one with the highest service `priority` is displayed,
and the order the service returned breaks ties.

## Join whitelist

`whitelist.yml` next to the config (created empty on first start, editable by hand) lists
the players who may join **without** a Silent Client staff rank. A whitelisted name skips
the API call completely, so it works during a Silent Client outage, and it is never
affected by `fail-closed`.

A whitelisted player who does happen to hold a rank still gets their rank displayed: the
plugin looks it up in the background after the join and applies the prefix when it
resolves.

## Chat icons

Minecraft can only draw an image if the client has a font that maps a character to it, so
the plugin builds a small resource pack containing the Silent Client rank icons as
bitmap-font glyphs and hands the same glyphs to chat:

```
icon  →  Rank  →  Username
[glyph] [Senior Tech Support] Zouhmi >> hello
```

Setup:

1. Start the server once. The pack is written to `plugins/SCSO/SCSO-Icons.zip` and the log
   prints its size and SHA-1 (`Icon resource pack ready: 10 icons, format 84, ...`).
2. Host that zip anywhere static (your web host, a CDN, `nginx`, anything that serves the
   file over HTTP) and set `resource-pack.url` in `config.yml` to the exact URL.
   Alternatively point `server.properties`' `resource-pack` + `resource-pack-sha1` at the
   same file — the plugin recognises its own pack by SHA-1 and says so in the log.
3. Run `/scso reload`. Players are offered the pack as they join, and once a client
   reports it loaded, that client sees icons.

Notes:

- `%icon%` in `chat-format` (the default) is replaced by the rank's glyph **per viewer**:
  a client that has not loaded the pack simply sees `[Rank] Name >> message` with no
  stray space, and never a missing-glyph box.
- Icons are only drawn when the pack really is available. If it cannot reach clients, the
  log says exactly what to do rather than silently showing nothing.
- `%icon%` also works in `tab-format`, but the tab list is a single value for all viewers,
  so only enable it there when every client has the pack.
- The icons ship inside the jar and are extracted to `plugins/SCSO/icons/`. Drop your own
  PNG in there (named after the rank, e.g. `owner.png`) and `/scso reload` to extend the
  pack; the atlas and font provider are regenerated with the glyph mapping.
- Ranks the service has no icon for (`Senior Admin`, `Owner`) are shown without an icon
  until you map them onto an existing one with `icon-ranks` in `config.yml`, e.g.
  `Senior Admin: admin`.
- Icon height/ascent and the pack format (`84` for Minecraft 26.1.2, read from that
  version's own `version.json`) live in `IconPack`; the format can be overridden with
  `resource-pack.format`.

## TAB (NEZNAMY) support

TAB owns the tab list while it is installed, and by design it blocks other plugins from
setting the vanilla player list name, so the rank is submitted through **TAB's own API**:

| Where | How |
| --- | --- |
| Tab list | `TabListFormatManager#setPrefix` / `#setName` / `#setSuffix` |
| Above the head | `NameTagManager#setPrefix` (when TAB's teams feature is enabled) |
| Chat | the plugin's own chat renderer, unchanged |

- `%name%` in `tab-format` is the **split point**: everything before it goes to TAB as the
  prefix, everything after it as the suffix, and the middle stays TAB's own rendering of
  the player's name.
- TAB loads players asynchronously, so a join is re-applied on TAB's `PlayerLoadEvent`, and
  every online player is re-applied on `TabLoadEvent` (TAB reloads drop API overrides).
- `softdepend: [TAB]` makes TAB load first. Nothing references TAB's classes unless TAB is
  installed, and TAB's API is **never shaded** into this jar (`provided` scope), as TAB
  requires.
- The log states what TAB actually offered, for example
  `Hooked into TAB (tab list formatting hooked, nametag prefixes hooked); ...`.

## Missing or unknown data

- **Not a Silent Client account** — the account endpoint rejects the name; the player is denied.
- **No staff rank** — denied unless whitelisted.
- **API unreachable / timed out / malformed JSON** — denied while `fail-closed: true`
  (the default). Set `fail-closed: false` to let players in during an outage instead.
- **Rank with a missing or malformed color** — falls back to white. Hex colors and rank
  names are validated and stripped of formatting characters before use, so a color or name
  can never inject formatting into chat or into TAB's string parser.

## Configuration

`config.yml` (created on first start):

| Key | Meaning |
| --- | --- |
| `api-base-url` | Silent Client API base. Must be HTTPS, no query/fragment/userinfo. |
| `connect-timeout-ms` / `request-timeout-ms` | Bounds for the lookup; both are clamped. |
| `fail-closed` | Deny logins when the API cannot answer (whitelisted names are unaffected). |
| `kick-message` / `lookup-failed-message` | Kick texts (`&` color codes supported). |
| `staff-ranks` | The allowlist. |
| `chat-icons` | Master switch for chat glyphs. |
| `chat-format` | Default `%icon% %rank% %name% >> %message%`. |
| `tab-format` | Default `%rank% %name%`. `%name%` splits the TAB prefix/suffix. |
| `icon-ranks` | Optional rank → icon overrides. |
| `tab-integration` / `tab-nametag-prefix` | TAB hook and the nametag prefix. |
| `resource-pack.url` / `.required` / `.prompt` | Where the pack is hosted, and how it is offered. |
| `resource-pack.format` | Resource pack format (84 = Minecraft 26.1.2). |
| `resource-pack.assume-loaded` | Draw icons for everyone when something else delivers the pack. |
| `cache.ttl-seconds` / `cache.max-entries` | How long a lookup is reused, and the cache bound. |

Upgrading from the old name: on first start the plugin copies `config.yml`,
`whitelist.yml` and custom icons from `plugins/SilentStaffMembers/` if
`plugins/SCSO/` does not have them yet, and upgrades `chat-format` when it still holds the
old default value (`%rank% %name% >> %message%`) so the icons appear without a manual edit.

## Building

Requires JDK 25 and Maven. The Paper API comes from `repo.papermc.io`, TAB's API from
JitPack; Gson is shaded and relocated so it cannot clash with another plugin's copy, while
TAB's API classes are left out of the jar entirely.

```bash
cd SilentStaffMembers
mvn clean package   # runs the unit tests, then writes target/SCSO-1.0.0.jar
```

Drop the shaded jar into `plugins/` on the Folia server.

## Tests

`mvn test` (43 tests) covers the policy, the Silent Client response parsing, the whitelist
store, the name formats and the TAB hook without a server:

- the twelve staff ranks match by name **and** by key, case/separator variants match, and
  `Player`, `Admin Assistant`, `Moderator` and empty input do not;
- rank payloads: highest priority wins, the service's order breaks ties, a malformed color
  falls back to white, and rank names are stripped of `&`/`§` formatting codes;
- the whitelist is matched case-insensitively, keeps insertion order through a save/load
  round trip, accepts a single hand-typed name, skips entries that are not names, and stays
  usable when the file cannot be read or written;
- `%icon%`: rendered when the viewer has the pack, dropped together with its space when the
  viewer does not, and the same for the TAB prefix/suffix split;
- the icon pack is built for real: the 10 icons land in one atlas (640×64, every cell
  non-empty), `pack.mcmeta` carries `pack_format` 84, the font provider's single row holds
  one glyph per icon starting at U+E000, the zip entry set is exactly the three files, the
  SHA-1 matches the file the plugin advertises, a rebuild of unchanged icons produces a
  byte-identical zip (so clients keep the cached pack), and a dropped-in `owner.png` is
  picked up while a corrupt PNG is skipped;
- the TAB hook's exact calls: tab prefix/name/suffix, the nametag prefix, a pack glyph in
  the prefix, "no rank, no calls", `PlayerLoadEvent` / `TabLoadEvent` handling, and that
  `close()` reverts the overrides and stops handling events.

Boot checks (not part of `mvn test`) run a real Folia `26.1.2` server twice, both with TAB
6.2.0 installed, and drive the console (`/scso pack`, the whitelist add/duplicate/invalid/
remove/list flow through the alias, `/scso reload`) while asserting the log output, the
generated pack (entries, `pack_format`, glyph count, atlas size, hash) and that a server
started with the old folder migrates its config and whitelist and upgrades the default
chat format. The second run also sets `server.properties`' `resource-pack` to the pack the
first run generated and asserts the plugin recognises it by SHA-1.

## Notes and limitations

- This is a **server plugin**, not a client mod: it protects the server regardless of what
  client a player uses.
- Verified usernames are cached for `cache.ttl-seconds` (default 300). Demoting someone
  takes effect after that window (or immediately with `/scso reload` while they are online).
- Verification adds one short network round trip to login; whitelisted players skip it.
- The TAB and icon integrations were verified at the API boundary (a recording fake TAB API
  and a real generated pack read back from its zip) plus live Folia boot checks. Whether a
  real client shows the glyph also depends on the client having loaded the pack, which no
  server-side test can prove.
