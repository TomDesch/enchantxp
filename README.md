# EnchantXP

An XP-only enchanting menu for exactly Spigot and Paper 1.12.2. No lapis, economy plugin or client mod required.

## Features

- Pick an inventory item, enchantment and level, then confirm the raw XP price.
- Upgrade equipment and stored book enchantments; compatible names, lore and other item metadata are preserved.
- Configure prices, above-vanilla levels up to 255, item limits, bookshelf pricing and optional level permissions.
- Customize menus, messages and private sounds. Sort enchantments and levels across pages.
- Edit settings in-game with typed chat prompts, draft validation and recoverable file backups.
- Creative purchases are free, but still obey item, price-validity, level and permission rules.

## Install and use

Stop the server, put the EnchantXP JAR in `plugins`, then start normally. Use a server-compatible Java runtime; this plugin targets Java 8. Do not use server `/reload`.

Right click an enchanting table, then select a compatible item from your inventory. Choose an enchantment and level, and left click Confirm. Back, Cancel and Escape never purchase. Keep your cursor empty and stay near the table.

A plain book stack converts one book per purchase and needs a separate empty storage slot. Equipped armor, offhand items, written books and stacked enchanted books are not supported.

## Commands and permissions

- `/enchantxp help`: lists the commands you can use; no administrative permission needed.
- `/enchantxp config`: in-game settings editor; requires `enchantxp.config` (default op).
- `/enchantxp reload`: validates the file and applies it without restarting; requires `enchantxp.reload` (default op), console supported.
- `enchantxp.use`: table access, enabled for everyone by default. Survival and creative only.

Optional level permissions check the exact target, for example `enchantxp.enchant.fortune.70`. Lower levels are not prerequisites. Parents `enchantxp.enchant.fortune.*` and `enchantxp.enchant.*` grant registered children. Exact nodes default to op; inherited permissions and explicit denials are resolved by Bukkit or your permissions plugin, with no OP bypass.

## Settings

Use `/enchantxp config` or edit `plugins\EnchantXP\config.yml` and run `/enchantxp reload`. The editor explains each setting. Left click edits or toggles; right click resets an override. Only Apply and save writes the draft.

- Prices use `base-xp * level * level`, with optional per-enchantment bases and cumulative level overrides. Upgrades charge the difference between current and target totals, then apply bookshelf pricing.
- `max-cost` hides options whose purchase charge exceeds the limit. Set it to `-1` to disable that configured ceiling; safe raw-XP integer bounds still apply. Level 70 is valid, and 255 is the hard level bound.
- `max-level: 0` disables that purchase option. Item-type caps override enchantment caps; an explicit ENCHANTED_BOOK cap takes priority over BOOK. Above-vanilla levels do not guarantee support in vanilla or third-party mechanics.
- Bookshelf pricing uses the vanilla 1.12.2 two-block-radius air-gap scan at table height and one above, with a hard maximum of 15. The balance bottle shows the effective count or reports that pricing is disabled.
- Green glowing toggles are enabled; red toggles are disabled. Named editor lists can be sorted alphabetically.
- Item limits change maximum enchantment levels, not XP prices: Diamond Sword -> Sharpness -> 10. A cap of 0 disables that option; resetting removes the item override and inherits the enchantment or vanilla cap.
- Text supports `&` colors and formatting, such as `&5`, `&6`, `&l`, `&o` and `&r`. Prompts say when `<blank>` is allowed; type `cancel` to return.

Menu appearance -> Lore formatting controls `lore-style.action-prefix` (default `\u00bb `, displayed as a right-pointing double angle) and the action, label, number and text colors. The prefix allows at most 16 visible characters; valid legacy color codes do not count. An empty prefix hides just the symbol. Empty role colors reset to white; formatting-only roles start from white. Table lore uses explicit `{action}`, `{label}`, `{number}` and `{text}` roles; unmarked custom prose is left alone. Formatting-only edits do not create `ui` overrides or clear inherited lore.

Right clicking an individual UI lore field restores an explicit copy of its shipped lines; Clear all lines intentionally keeps it empty. Use default appearance resets both menu overrides and shared lore formatting. Use default icon resets only that icon.

Existing explicit table lore stays unchanged when upgrading. To adopt the new presentation, compare your intended defaults with packaged `config.yml` and replace only matching old shipped lines with their role-marked equivalents, including `ui.icons.instructions.lore`. Keep customized wording. Command help now sends its heading and each permitted command separately; `{commands}` remains permission-filtered.

The required integer `config-version: 1` identifies the configuration schema, not the plugin release or save count. Before an incompatible upgrade, preserve your old file, compare the new defaults and follow the release instructions before setting the documented schema. There is no automatic migration or unconditional compatibility guarantee.

Invalid startup configuration disables the plugin; a bad reload retains healthy live settings and leaves your file untouched. Saves reject detected external edits and retain at most two owned exact-byte `config.yml.backup-*` generations. Explicit saving can normalize YAML and remove comments. If feedback says "Saved, but not applied", check the log and reload. Keep your own backups for long-term recovery.

## Support

[Support and links](http://www.stealingdapenta.be/)

Alpha builds are unstable. No project license has been selected.

Build and source guidance: [CONTRIBUTING.md](CONTRIBUTING.md).
