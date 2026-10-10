# <img src="https://silentclient.net/assets/logo.png" alt="Silent Client" width="32" height="32" style="vertical-align: middle;"> SilentStaffMembers

A Folia plugin that restricts access to Silent Client staff only. It checks a player before login, then shows their rank in chat, the tab list, and above their head.

## Features

Staff only access

Rank display in chat, tab list, and nametag

TAB support when installed

Bounded API lookups and caching

Safe handling of rank colors and formatting

## How it works

1. A player tries to join.
2. The plugin checks their account on Silent Client.
3. If they are not staff, the login is denied.
4. If they are staff, the rank is cached and shown in-game.

## Supported ranks

Tester, Tech Support, Senior Tech Support, Admin, Developer, Senior Tester, Senior Admin, Client Mod

## Configuration

Edit config.yml after the first start.

- api-base-url
- fail-closed
- cache.ttl-seconds
- chat-format
- tab-format
- tab-integration

Reload with /silentstaff reload.

## Installation

Requires JDK 25 and Maven.

```bash
cd SilentStaffMembers
mvn clean package
```

Place the built jar in your server plugins folder.

Compatible with Paper/Folia API 26.1.2 and TAB 6.2.0.

## Credits

Made by zouhmi

https://zouhmi.com
