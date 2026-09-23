# Simple Economy Plugin

A simple economy for Paper: balances, an auction house, buy orders, and a fixed-price /sell.
Every menu is a plain chest inventory - no custom dialogs or text-entry popups - so it works the
same for Bedrock players connecting through Geyser as it does for Java players. Anything that needs
typed text (a price, an item, an amount) is a command; menus only need clicks.

## Build
**GitHub:** push this project to a repo. The Build action runs automatically; download the jar from
Actions > latest run > Artifacts.

**Locally:** `gradle build`, jar ends up in build/libs/. Drop it into your server's `plugins/` folder.

If dependency resolution or plugin loading complains about the version, edit:
- `build.gradle.kts` -> paper-api version and Java toolchain
- `src/main/resources/plugin.yml` -> api-version

## Commands
| Command | What it does |
|---|---|
| /bal [player] | Check balance |
| /pay <player> <amount> | Send money (accepts 1k, 2.5m, 1b) |
| /baltop | Leaderboard (chest menu with player heads) |
| /eco give/take/set <player> <amount> | Admin (simpleeconomy.admin) |
| /eco sellprice <item> <price\|remove> | Set (or remove) an item's base /sell price |
| /eco sellmultiplier [value] | View or set the global sell multiplier |
| /ah | Open the auction house |
| /ah sell <price> or /sell <price>... | Hold the item, run `/ah sell <price>`; a chest menu confirms before it lists |
| /ah search <text> | Search the auction house |
| /orders | Browse buy orders, click to fill from your inventory |
| /orders create <item> <amount> <price each> | Propose an order; a chest menu confirms before the money is held |
| /sell | Shows the value of what's in your hand and in your inventory |
| /sell hand | Sell the item in your hand at its fixed base price |
| /sell all | Sell every plain, sellable item in your inventory |

## How /sell pricing works
`prices.yml` holds a fixed base price per item, seeded with a starter list on first run. Players
never set these - only an admin can, via `/eco sellprice` or by editing the file - so nobody can
"sell" a dirt block for a fortune. `sell.multiplier` in `config.yml` is a single global knob an
admin can tune to adjust the whole economy without touching every item. Only plain items (no
rename, no enchants) can be sold.

The auction house (`/ah`) is unrelated to this: it's where players set their own prices for their
own listings, same as before.
