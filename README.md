# SilentStaffMembers

A Folia plugin that keeps a server staff-only by asking Silent Client who a player is
**before they are allowed in**, then showing that player's Silent Client rank — in the
service's own color — in chat, the tab list and above their head.

It also hooks into **TAB (NEZNAMY)** when that plugin is installed, so the rank still
appears in TAB's tab list instead of being blocked by it.

Built and verified against **Paper/Folia API `26.1.2.build.74-stable`** (Java 25), and
boot-tested on a real Folia `26.1.2` server both with and without TAB 6.2.0.

## What it does

1. `AsyncPlayerPreLoginEvent` fires while the connection is still logging in. The plugin
   looks the username up on Silent Client and waits (bounded) for the answer.
2. If the account holds **no** staff rank, the login is denied with the configured kick
   message. Non-staff never reach the world.
3. If the account holds a staff rank, the login is allowed and the rank is remembered for
   that session.
4. On join, the rank is written as the player's display name, tab-list name and chat
   prefix, colored with the hex color the service sends with the rank — and pushed through
   TAB's API as well when TAB owns the tab list.

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

`Tester`, `Tech Support`, `Senior Tech Support`, `Admin`, `Developer`, `Senior Tester`,
`Senior Admin`, `Client Mod`

When an account holds several, the one with the highest service `priority` is displayed,
and the order the service returned breaks ties.

## TAB (NEZNAMY) support

TAB owns the tab list while it is installed, and by design it blocks other plugins from
setting the vanilla player list name. So when TAB is detected the plugin submits the rank
through **TAB's own API** instead of fighting it:

| Where | How |
| --- | --- |
| Tab list | `TabListFormatManager#setPrefix` / `#setName` / `#setSuffix` |
| Above the head | `NameTagManager#setPrefix` (when TAB's teams feature is enabled) |
| Chat | the plugin's own chat renderer, unchanged |

- `%name%` in `tab-format` is the **split point**: everything before it is handed to TAB
  as the tab prefix, everything after it as the suffix, and the middle stays TAB's own
  rendering of the player's name. `tab-format: '%name% %rank%'` therefore puts the rank
  after the name without fighting TAB's formatting.
- TAB loads players asynchronously, so a join is re-applied on TAB's `PlayerLoadEvent`,
  and every online player is re-applied on `TabLoadEvent` (TAB reloads drop API overrides).
- `/silentstaff reload` re-detects TAB, so installing or removing TAB only needs a reload.
On startup the plugin reports exactly what TAB offered, so a server owner can see whether
both surfaces are live:

```
[SilentStaffMembers] Hooked into TAB (tab list formatting hooked, nametag prefixes hooked); staff ranks are shown through TAB's API.
```

- `softdepend: [TAB]` makes TAB load first. Nothing here references TAB's classes unless
  TAB is actually installed, and **TAB's API is never shaded** into this jar (the
  dependency is `provided`), as TAB requires.
- Set `tab-integration: false` to leave TAB completely untouched (the vanilla list name
  is then TAB's problem).

## Missing or unknown data

- **Not a Silent Client account** — the account endpoint rejects the name; the player is denied.
- **No staff rank** — denied.
- **API unreachable / timed out / malformed JSON** — denied while `fail-closed: true`
  (the default). Set `fail-closed: false` to let players in during an outage instead.
- **Rank with a missing or malformed color** — falls back to white. Hex colors and rank
  names are validated before use, so a color or name can never inject formatting into
  chat or into TAB's string parser.

## Configuration

`config.yml` (created on first start):

| Key | Meaning |
| --- | --- |
| `api-base-url` | Silent Client API base. Must be HTTPS, no query/fragment/userinfo. |
| `connect-timeout-ms` / `request-timeout-ms` | Bounds for the lookup; both are clamped. |
| `fail-closed` | Deny logins when the API cannot answer. |
| `kick-message` / `lookup-failed-message` | Kick texts (`&` color codes supported). |
| `staff-ranks` | The allowlist. |
| `chat-format` | Default `%rank% %name% >> %message%`. |
| `tab-format` | Default `%rank% %name%`. `%name%` splits the TAB prefix/suffix. |
| `tab-integration` | Push ranks through TAB's API when TAB is installed. |
| `tab-nametag-prefix` | Also show the rank prefix above the player's head via TAB. |
| `cache.ttl-seconds` / `cache.max-entries` | How long a lookup is reused, and the cache bound. |

`/silentstaff reload` re-reads the config, re-detects TAB and re-verifies everyone who is
online (permission `silentstaffmembers.admin`, default op). Players who fail are kicked.

## Building

Requires JDK 25 and Maven. The Paper API is resolved from `repo.papermc.io`, TAB's API
from JitPack; Gson is shaded and relocated so it cannot clash with another plugin's copy,
while TAB's API classes are left out of the jar entirely.

```bash
cd SilentStaffMembers
mvn clean package   # runs the unit tests, then writes target/SilentStaffMembers-1.0.0.jar
```

Drop the shaded jar into `plugins/` on the Folia server. `plugin.yml` declares
`folia-supported: true`, `api-version: '26.1.2'` and `softdepend: [TAB]`.

## Tests

`mvn test` covers the policy and response parsing without a server, and the TAB
integration against a fake TAB API that records every call the plugin would make:

- every one of the eight staff ranks matches by name **and** by key;
- case and separator variants match; `Player`, `Admin Assistant` and empty input do not;
- a rank payload with a normal player rank plus a staff rank yields non-staff classification
  when only the player rank is present, and the highest-priority rank is displayed when
  several staff ranks are held (the service's order decides ties);
- a malformed color (`red;op=true`) falls back to white, and rank names containing `&`
  or `§` are stripped of their formatting codes;
- `tab-format` splitting: default, reordered, extra text, and empty formats;
- the TAB hook's exact calls — tab prefix/name/suffix, the nametag prefix, the "no rank,
  no calls" case, `PlayerLoadEvent` / `TabLoadEvent` handling, and that `close()` reverts
  the overrides and stops handling events.

Boot checks (not part of `mvn test`) run a real Folia `26.1.2` server twice - once without
TAB and once with TAB 6.2.0 installed - then send `/silentstaff reload` through the console
before stopping. Both runs assert that the plugin enables cleanly and that the reload path
works; on the TAB run the log must show TAB loading **before** the plugin, the hook
connecting on enable and again after the reload, with no errors in either run.

## Notes and limitations

- This is a **server plugin**, not a client mod: it runs on the Folia server, so it protects
  the server regardless of what client a player uses.
- Verified usernames are cached for `cache.ttl-seconds` (default 300). Demoting someone takes
  effect after that window (or immediately with `/silentstaff reload` while they are online).
- Verification adds one short network round trip to login. Players whose Silent Client lookup
  is slow may need the timeouts raised.
- The TAB integration was verified at the API boundary (a recording fake API in the unit
  tests, plus a live Folia + TAB boot check). Showing the result to a real client still
  depends on TAB's own config: if TAB's `tablist-name-formatting` feature is disabled,
  TAB offers no format manager and the plugin logs that it keeps the vanilla list name.
- TAB API overrides are intentionally temporary: TAB drops them on its own reload, which is
  why they are re-applied from `TabLoadEvent`.
