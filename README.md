# EnchantXP 0.2.0-alpha.3

Enchanting-table menus for exactly Spigot and Paper 1.12.2. Select an inventory
item, a vanilla enchantment, a level, then confirm payment in raw XP. No lapis,
economy, custom effects, client mod or extra plugin is required.

## Build and install

Build with JDK 21 and the included Maven 3.9.11 wrapper. The plain, unclassified
JAR targets Java 8 (bytecode major 52); Bukkit is provided, not bundled.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -version
.\mvnw.cmd -version
.\mvnw.cmd -B -ntp clean verify
python scripts\verify_artifact.py
python scripts\test_verify_artifact.py
```

Preserve old release/playtest JARs outside `target` before a clean build. Copy only
`target\enchantxp-0.2.0-alpha.3.jar` into `plugins` while the server is stopped, then start
normally. Do not use server `/reload`.

Build and server JVMs are separate. Spigot's BuildTools
guidance selects JDK 8 below Minecraft 1.17; current Paper guidance recommends
Java 11 for 1.12 to 1.16.4. Paper on Java 11 remains untested here. Modern
Minecraft versions and JDK 21 as a 1.12.2 runtime are not supported claims.
There is no api-version, PDC, NMS, reflection, Paper-only API or runtime library.
Legacy text and enchantment APIs are intentional 1.12.2 exceptions.

## Use

- In survival or creative with `enchantxp.use` (default true), right click an enchanting
  table with any item or an empty main hand. Previously cancelled interactions
  are respected. Adventure and spectator cannot purchase, including ops.
  Creative purchases cost zero and never read or write player XP, but retain
  item, cap, conflict, access and configured price-validity checks. The balance
  uses `ui.unlimited-balance` (default `Unlimited`).
- Left/right click or shift-left/right click a compatible storage item below the menu, then
  choose an enchantment and level. Selection leaves the real item in place.
  The empty input beside the anvil and the top preview are display-only.
  Green Confirm purchases; Back, Cancel and Escape do not.
- Footer positions relative to the final row are Back 0, Previous 2, Cancel 4,
  Info 5, Confirm 6, Sort 7, Next 8. Sort cycles default/ascending/descending,
  sorting the entire list before pagination and returning to page one.
  Names ignore colors/case with canonical-name ties; levels sort numerically.
  Sort exists only on enchantment and level lists with at least two available
  choices across all pages; otherwise its slot is normal inert decoration.
- Plain BOOK stacks 1..64 and single ENCHANTED_BOOK inputs support compatible
  stored enchantments. A purchase converts one plain book. A stack requires a
  distinct empty main/hotbar storage slot (0..35); otherwise nothing changes.
  A single book replaces its own source, even in full storage. Equipped armor,
  offhand, other stacks, stacked enchanted books and written books are rejected.
- Only normal left clicks navigate or confirm. Right and shift clicks work only
  for storage item selection and never move the actual stack. Movement, drags,
  number/drop actions and offhand swaps are blocked. Keep the cursor empty and held hotbar
  slot unchanged; the selected source may be a different storage slot.
- Confirmation rechecks the full item snapshot, price, XP, output space,
  resolved target-level permission, game mode, session/player identity, loaded table chunk and world,
  and five-block distance from player feet to table center. This is a strict
  menu-retention policy, not vanilla interaction reach; step closer and reopen
  after rejection. External inventories are never replaced by deferred work.
  A changed cost or survival/creative mode refreshes the displayed quote without
  charging; explicitly confirm again. Insufficient XP shows a configurable
  Barrier (`ui.icons.insufficient`) with `{missing}` raw XP. Clicking it gives
  feedback without consuming the session. Becoming affordable first refreshes
  the purchase icon; it never silently pays on that first click.
- Close, quit, kick, death, successful config reload and disable fence sessions
  and queued actions. Back/Cancel fence already queued confirmation.
  List sorting fences queued list selections.
  Duplicate confirms cannot purchase twice. Metadata is changed on clones:
  name, lore, durability, flags and compatible existing enchants survive.
  GUI decoration never becomes the purchased item's metadata.

## Configuration and reload

`plugins\EnchantXP\config.yml` is created once and never rewritten by reload.
Edit it, then run `/enchantxp reload` as console or with `enchantxp.reload`
(default op). Other arguments show usage; permitted senders can tab-complete
`config` and `reload` according to their permissions. Completion is case-insensitive;
extra arguments have no suggestions. Reload and editor saves share one queue slot.

### In-game settings

Run `/enchantxp config` in-game with `enchantxp.config` (default op).
The pink settings menu covers prices, shelves, permissions, sounds, all 30
enchantments, sparse level prices, item caps, menu appearance and every message.
Lists have Previous/Next buttons when needed. Left click a setting to edit it;
booleans toggle immediately in your private draft. Right click resets a setting
or removes an override. "Uses" shows an inherited value; "Set to" shows an override.
BOOK caps also cover enchanted books; an explicit ENCHANTED_BOOK cap takes priority.

Numbers, names and text use private chat prompts. Type `cancel` to return without
changing that value, `<blank>` for empty text, or start with `\` to use either word
literally. Lore has separate add/edit/delete controls, including blank lines.
Prompts accept up to 256 characters. Closing for a prompt keeps your draft;
Escape from the menu or Discard throws away unsaved changes. Drafts last 15 minutes;
up to 64 may be open. Quit, kick, disable and any successful reload invalidate them.

Only Apply and save writes anything. You can make related changes before applying;
the complete draft is validated together. Invalid drafts stay editable. The editor
keeps the loaded YAML document, not a fully expanded copy of the effective defaults.
Omitted settings remain omitted unless you edit them. Existing lore rules still
apply: adding the first `ui` or icon section makes its omitted lore empty.
"Use default icon" removes the whole icon override; "Use default appearance" removes
the whole `ui` section, including its height.

Reads and writes are off-thread; YAML validation and live settings changes are on
the server thread. Files and save payloads are limited to 1 MiB of valid UTF-8.
Saving checks both the draft generation and a SHA-256 hash of the original bytes.
A manual edit, another saved draft or even a same-byte reload requires reopening;
the editor does not overwrite a detected change.

Apply stages a same-directory file, rechecks access, then keeps a unique
`config.yml.backup-<id>` containing the exact original bytes before atomic file
replacement and readback. Filesystems without atomic replacement are rejected;
there is no non-atomic fallback. Backups are not automatically deleted.
Explicit saving may normalize YAML formatting and remove comments. Opening,
editing, discarding and reloading never rewrite the file.

Disk replacement and live Bukkit settings cannot be one atomic transaction.
If saving succeeds but publication fails, feedback says "Saved, but not applied";
check the log and use `/enchantxp reload`. Backups remain available. The plugin
never rolls back over a later external edit. A disconnect, revoked permission,
cancel or disable known before the final commit gate prevents replacement.
A race after that gate may still save without applying; simultaneous external
writers and crash/power-loss durability are not guaranteed.

File reads run off-thread; YAML/settings validation and publication run on the
server thread. Permission is checked again before publication; revoked permission,
a disconnected player or a replaced connection discards the request without
closing old menus. Invalid YAML, unreadable files, missing required prices, invalid
values, unknown defined-section keys, material/enchantment names or placeholders
keep the old settings and menus. The sender sees the config path, and the log
records the specific cause. A successful reload invalidates old menus and pending
work before applying the new snapshot; reopen the table. In-flight callbacks
cannot apply after disable. Initial invalid configuration still disables the plugin.

- Required `base-xp` and `max-cost` are whole raw XP values in 1..1000000;
  base must not exceed max-cost. Old explicitly configured values stay unchanged.
- Price precedence is exact cumulative `enchantments.NAME.levels.'N'`, then
  explicitly configured `enchantments.NAME.base-xp * N * N`, then global
  `base-xp * N * N`. Missing enchantment bases, even deleted shipped special
  bases or entire enchantment sections, use the operator's global price.
  Fresh configs still include special prices: Mending 250000, Fortune 25000,
  Silk Touch 150000, Infinity 200000, Aqua Affinity 75000 and Flame 150000.
- Upgrades cost the difference between the old and new level at today's prices,
  including enchantments obtained elsewhere. Previous payments are not tracked
  or refunded. Fortune II to III has a base difference of 125000 XP.
  Sparse level overrides must strictly increase across 1..255, including formula
  boundaries. `max-cost` limits the positive purchase difference, not cumulative
  totals; unavailable choices are never discounted or clamped.
- `bookshelf-pricing.enabled` defaults true. The default whole-percent settings
  are `zero-shelf-penalty-percent: 150`, `per-bookshelf-reduction-percent: 10`,
  `max-bookshelves: 15`. The percentage keys accept 0..1000000 and the cap 1..15.
  Charge is `ceil(baseDifference * (100 + max(0, penalty - min(shelves, cap) * reduction)) / 100)`.
  Thus zero shelves costs 2.5x base, one costs 2.4x and 15 costs base. Round the
  difference once, not two cumulative values. Apply `max-cost` afterwards.
  Disabled returns the base difference without scanning the world.
  Scans use the exact 1.12.2 air-gap rule, 16 outer-ring columns at table height
  and one above (32 unique positions), capped at 15, only on the server thread.
  Unloaded chunks and Y outside 0..255 are not read or loaded.
- Cap precedence is `items.MATERIAL.max-levels.NAME`, then
  `enchantments.NAME.max-level`, then the native vanilla maximum.
  Caps are 0 (no purchases) or API start level..255. `enabled: false` cannot be
  undone by an item cap. An explicit ENCHANTED_BOOK cap wins over BOOK for both
  book forms; otherwise both inherit BOOK before enchantment/vanilla defaults.
  Existing vanilla enchants survive disabled/lowered purchase caps. Existing
  above-vanilla levels require an effective cap at least that high.
- `ui.rows` is a whole integer 3..6 (default 6): respectively 7/14/21/28 content
  slots, with all controls in the last row. Preview remains at top slot 4.
  There are no arbitrary slot settings.
- `ui.titles`, stage names/lore, `ui.icons` and friendly enchantment names control
  presentation. Icons use legacy material/data/name/lore; choice books remain
  ENCHANTED_BOOK. Templates use `&` colors and the documented tokens in config.yml.
  Values are inserted once, never recursively expanded. Inapplicable tokens
  render blank. Titles are cropped to 32 UTF-16 code units.
- To remove lore, use `lore: []`. Deleting an icon's lore (or YAML null) also
  clears it when that icon section remains explicitly present. An entirely
  omitted icon section uses packaged defaults. Deleted stage lore such as
  `ui.review-lore` is empty when `ui` is present; an entirely omitted `ui` uses
  packaged defaults. Empty-string lore is invalid; lists must contain strings.
  No extra empty flag is needed. Other missing optional leaves use defaults.
- `messages` includes reload feedback. Set any feedback message to `''` to
  suppress chat, independently of sound. `messages.prefix` is automatically
  prepended once to every nonblank player/console message, not UI lore.
  Old `{prefix}` placeholders render empty before that step; prefix-only or
  color/whitespace-only bodies stay silent. Only the exact old shipped success
  and eligibility templates receive in-memory compatibility fixes. Operator
  custom content is kept; files change only after an explicit editor save.
  Editor feedback also accepts `{detail}`. `{path}` is allowed only in `messages.reload-failure`; `{sort}`
  only in the sort icon. Unknown tokens fail validation. F3+H item IDs/NBT
  counts are client-generated advanced tooltips, not removable Bukkit lore.
- `sounds.enabled` defaults true. Each of `sounds.click`, `sounds.success` and
  `sounds.insufficient` has `sound`, `volume`, `pitch`. Defaults are legacy
  `UI_BUTTON_CLICK` (0.5/1.0), `BLOCK_ENCHANTMENT_TABLE_USE` (1/1) and
  `ENTITY_VILLAGER_NO` (1/1). Sounds are private to the player, not broadcast.
  Volume is finite 0..4 and pitch finite 0..2. Empty sound names disable that
  category. Confirm produces success or denial, not an additional click;
  filler and repeated pending clicks are silent. Nonempty storage selection
  attempts and accepted navigation play one immediate click before deferred
  validation/navigation. Selection feedback does not promise available upgrades.
  The click pitch default is now 1.0 for a less drawn-out click; existing explicit
  `sounds.click.pitch: 0.5` stays unchanged. Set it to `1.0` in the editor or file
  and apply/reload explicitly to try the new tuning. The config editor itself
  does not play menu sounds. Network and client audio scheduling can still
  affect perceived timing; authenticated-client listening is needed to assess it.

### Optional exact-level permissions

`permissions.enabled` defaults false for backward-compatible open access.
When enabled, only the requested target is checked, for example
`enchantxp.enchant.sharpness.4`; levels 1..3 are not prerequisites.
Denied levels and enchantment types with no permitted legal levels are hidden.
The check uses `player.hasPermission(exactLeaf)` again at confirmation and before
mutation, without an operator or manual wildcard bypass.

Leaves default to op. Registered `enchantxp.enchant.sharpness.*` parents contain
the configured effective levels, including higher item caps up to 255;
`enchantxp.enchant.*` contains all 30 enchantment parents. Parent defaults are
false. Standard Bukkit child/default/attachment permissions are used; the
permission system owns the resolved leaf and negative precedence. This does not
promise every native attachment ordering gives an exact negative priority.

Nodes are registered only on enable of this feature, grow on valid cap raises
and remain owned until plugin shutdown, even after lowering caps or turning
the feature off. Policy still limits offered levels. Existing wildcard
attachments are recalculated when children grow, without relogging.
Foreign nodes in this namespace reject the candidate before menus are invalidated;
failed registration rolls back new nodes and child maps. Shutdown preserves
foreign replacement instances and removes owned registrations/defaults.

Stable slugs (unaffected by configurable display names):

```text
PROTECTION_ENVIRONMENTAL=protection  PROTECTION_FIRE=fire_protection
PROTECTION_FALL=feather_falling      PROTECTION_EXPLOSIONS=blast_protection
PROTECTION_PROJECTILE=projectile_protection  OXYGEN=respiration
WATER_WORKER=aqua_affinity          THORNS=thorns
DEPTH_STRIDER=depth_strider         FROST_WALKER=frost_walker
BINDING_CURSE=binding_curse         DAMAGE_ALL=sharpness
DAMAGE_UNDEAD=smite                 DAMAGE_ARTHROPODS=bane_of_arthropods
KNOCKBACK=knockback                 FIRE_ASPECT=fire_aspect
LOOT_BONUS_MOBS=looting             SWEEPING_EDGE=sweeping_edge
DIG_SPEED=efficiency               SILK_TOUCH=silk_touch
DURABILITY=unbreaking              LOOT_BONUS_BLOCKS=fortune
ARROW_DAMAGE=power                 ARROW_KNOCKBACK=punch
ARROW_FIRE=flame                   ARROW_INFINITE=infinity
LUCK=luck_of_the_sea               LURE=lure
MENDING=mending                   VANISHING_CURSE=vanishing_curse
```

Example above-vanilla configuration (merge these keys, do not duplicate sections):

```yaml
base-xp: 10000

max-cost: 1000000

enchantments:
  DAMAGE_ALL:
    max-level: 10
    base-xp: 100

ui:
  rows: 3
  icons:
    info:
      lore: []
```

At 15 shelves (or with bookshelf pricing disabled), this offers Sharpness X for
10000 raw XP from unenchanted compatible inputs; IX to X costs 1900.
At zero shelves the default modifier makes those 25000 and 4750.
Vanilla caps remain the default for other enchants. Unsafe
level application is validated and read back on normal items and stored books;
this does not promise every vanilla combat, anvil or third-party mechanic
honors levels above vanilla maxima.

## Accounting and validation limits

Survival spendable XP comes from level and progress using vanilla piecewise curves,
rounded to whole raw points, never lifetime getTotalExperience. After spending,
level/progress/total score are reconstructed. Invalid/overflowing balances are
refused. Single-item failure first attempts original-source restoration and
requires a fresh exact readback before refunding XP. Split-book failure clears
the output first and requires fresh null/AIR proof before restoring source/XP.
Retained, mismatched or unreadable output does not mint resources or receive a
refund. All safe rollback setters are attempted; suppressed failures are logged.
Partial API failure may still require operator recovery; no durable crash-atomic
cross-system item/XP transaction is claimed.

## Version policy

Current development uses pre-1.0 alpha versions, starting at 0.2.0-alpha.1.
Increment alpha.N for iterations of the same planned version. Before 1.0,
patch versions cover fixes and minor versions cover additive features or breaking
config-schema, command/permission or API changes; document breaks explicitly.
Alpha versions are unstable and do not promise a stable public contract.
Version 1.0.0 is reserved for a future explicitly approved stable release.
After 1.0, fixes are patches, additive features are minors and breaking contracts
require a major. The earlier 1.0.1 label was an owner-approved exception and is
retained only on its historical evidence and artifacts, not retroactively renamed.

## Future design only

These are not available settings or implemented features. Keep the default
XP-only flow; no database, random-drop system or separate recipe plugin is planned.

1. Material requirements beyond the implemented optional target-level permissions:
   optional vanilla material/count requirements.
   Exact target-level recipes override enchantment defaults. Upgrades consume the
   target recipe only, not every preceding tier; there are no historical material
   rebates. Any cumulative-tier mode would need a separate explicit design.
   Confirm must recheck permissions, aggregate material counts excluding input and
   result slots, source and output space. Prepare a whole-inventory clone plan,
   then combine XP/material/output mutation with conservative compensation.
   Insufficient resources change nothing; duplicate/cancel/reload fences still apply.
2. Optional item-provider adapters: stable provider item IDs through an official,
   exact-version-compatible API with optional softdepend/provided dependencies.
   Missing provider or item ID must fail closed for the affected recipe, never
   make it free or substitute another item. Name/lore alone cannot identify value.
   [MythicDrops](https://mythicdrops.dev/) describes tiers, socket gems and potion
   effects; its [repository](https://github.com/MythicDrops/MythicDrops) advertises
   an API, but no exact 1.12.2 adapter version has been verified. First obtain the
   actual plugin/version, stable API and item IDs, then test native inventory,
   anvil and trading behavior in isolation. Metadata preservation is not proof of
   integration. This legacy API has no PDC; no NMS/reflection/NBT workaround is planned.
3. Gear potion/combat effects belong in an optional integration/addon, not this
   core enchanter. Agree effects/balance with the owner first; use stable effect
   IDs, explicit stacking/amplifier limits and equipment activation rules.
   Verify death/quit/disable cleanup without blindly removing other plugins'
   potions. Acceptance requires isolated provider tests and real-client checks,
   not just retained metadata.

## Sources and license

- [Pinned Spigot 1.12.2 sources](https://hub.spigotmc.org/nexus/content/repositories/snapshots/org/spigotmc/spigot-api/1.12.2-R0.1-SNAPSHOT/spigot-api-1.12.2-R0.1-20180712.012057-156-sources.jar)
- [Spigot BuildTools Java guidance](https://www.spigotmc.org/wiki/buildtools/)
- [Paper Java guidance](https://docs.papermc.io/paper/getting-started/)
- [Semantic Versioning](https://semver.org/)

No project license has been selected. Maven wrapper Apache-2.0 notices are
retained; the wrapper is build tooling, not bundled in the plugin.
