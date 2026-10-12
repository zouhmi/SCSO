<p align="center">
  <img src="assets/logo.png" width="128" alt="Silent Client logo">
</p>

# SCSO | Silent Client Staff Only

<p align="center"><a href="https://silentclient.net">Silent Client</a></p>

A Folia plugin that restricts server access to Silent Client staff, displays their ranks in chat and player lists, and supports optional TAB integration.

## Features

- Verify staff ranks through the Silent Client API before login.
- Deny non-staff players unless they are whitelisted or have a rank pin.
- Display rank names and service colours in chat, the tab list, and above players.
- Configure staff ranks, formats, colours, priorities, and access rules.
- Integrate with TAB by NEZNAMY when installed.
- Cache lookups to reduce API requests.
- Safely handle API failures with configurable fail-closed behaviour.

Tested on Folia `26.1.2` with Java 25, with and without TAB `6.2.0`.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/scso reload` | `scso.reload` | Reload config and recheck online players |
| `/scso sync` | `scso.sync` | Refresh ranks for online players |
| `/scso check <player>` | `scso.info` | Check a player's rank using the API |
| `/scso info [player]` | `scso.info` | Show rank and access details |
| `/scso whitelist add <player>` | `scso.whitelist` | Allow a player to join |
| `/scso whitelist remove <player>` | `scso.whitelist` | Remove whitelist access |
| `/scso whitelist list` | `scso.whitelist` | List whitelisted players |
| `/scso rank set <player> <rank>` | `scso.rank` | Set a displayed rank |
| `/scso rank remove <player>` | `scso.rank` | Remove a rank pin |
| `/scso rank list` | `scso.rank` | List rank pins |

Aliases: `/staff`, `/silentstaff`, `/scso wl`, and `/scso ranks`.

`scso.admin` is the parent permission and defaults to operators.

## Configuration

The plugin creates its configuration files in `plugins/SCSO/`.

- `config.yml`: staff ranks, formatting, API timeouts, cache, custom ranks, TAB settings, and failure behaviour.
- `whitelist.yml`: players allowed to join without a staff rank.
- `ranks.yml`: player rank pins.

Example custom ranks:

```yaml
custom-ranks:
  Senior Tech Support:
    color: '#00AAFF'
  Head of Staff:
    color: '#FF0000'
    priority: 90
  Legend: '#FFAA00'
```

Rank pins can grant access by default. Set `rank-override-grants-access: false` if pins should only change the displayed rank.

## Rank verification

SCSO checks the Silent Client API to identify the account and retrieve its ranks. The account username must match the Minecraft username, ignoring case.

The plugin recognises these staff ranks by default:

`Tester`, `Senior Tester`, `Tech Support`, `Senior Tech Support`, `Client Mod`, `Senior Client Mod`, `Tester Manager`, `Client Mod Manager`, `Admin`, `Senior Admin`, `Developer`, `Owner`

If several staff ranks are found, the rank with the highest service priority is displayed.

If the API is unavailable, logins are denied when `fail-closed: true`. Whitelisted players can still join during an outage.

## Build

Requirements: JDK 25 and Maven.

```bash
mvn clean package
```

The plugin jar is created in `target/`. Put it in your server's `plugins/` directory.

## Tests

Run unit tests with:

```bash
mvn test
```

The project includes unit tests for rank verification, configuration, whitelist and rank-pin storage, formatting, and TAB integration. It has also been boot-tested on Folia with and without TAB installed.

## Notes

- SCSO is a server plugin, not a client mod.
- Rank lookups are cached for 300 seconds by default.
- Use `/scso sync` to refresh online players immediately.
- Whitelisted players skip the login API check.
- The Java package is `net.zouhmi.silentstaffmembers`.
