# Build and source guide

## Compatibility and verification

Compile with JDK 21 and the Maven 3.9.11 wrapper. The pinned provided Spigot API targets exactly Spigot and Paper 1.12.2; the plain unclassified JAR emits Java 8 bytecode. Do not add api-version, NMS, reflection, PDC, Adventure or Paper-only APIs.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -version
.\mvnw.cmd -version
.\mvnw.cmd -B -ntp clean verify
python scripts\verify_artifact.py
python scripts\test_verify_artifact.py
```

Preserve retained artifacts outside `target` before cleaning. Build and server JVMs are separate. Test the packaged JAR on both exact platforms; a mocked test or Paper pass does not prove Spigot behavior or real-client presentation. No new runtime dependencies are required.

GitHub Actions runs wrapper verification and the two artifact-check scripts on Linux and Windows with JDK 21. After inspection, it retains only the exact plain deployment JAR for seven days. Action releases are pinned to upstream commit SHAs; update pins only after checking their upstream release tags. Hosted CI covers unit tests and packaging; native server QA runs separately.

## Source map

Under `io.github.stealingdapenta.enchantxp`, only `EnchantXpPlugin` lives in the root and wires lifecycle, listeners and commands.

- `administration`: editor drafts, page definitions, serialized reload/save and file ownership. Drafts and save tickets stay together, package-private.
- `menu`: events, session identity, quotes and transactions. `MenuRenderer` builds unpublished views using immutable route/pagination values; `TableMenus` owns publication and lifecycle fences.
- `configuration`: immutable validated settings; `configuration.values` provides strict optional/required YAML readers and fresh packaged defaults.
- `presentation`: immutable text/sound settings, shared explicit lore roles and menu stages. Lore-style settings live outside `ui` to preserve its existing omitted-lore contract.
- `enchanting`: enchantment rules, cumulative prices, shelves, permissions and raw XP. Menu admission stays package-private with its purchase workflow.

Dependencies flow from administration to menu/configuration, from menu to configuration/presentation/enchanting, and from configuration to presentation/enchanting. Presentation uses enchanting names; both depend on shared YAML readers below them. Rules and presentation never depend on controllers. Tests mirror these boundaries; reusable YAML and metadata fixtures live in `testsupport`.

Write useful Javadoc contracts in Markdown-readable paragraphs, lists and backticks inside `/** ... */`, retaining `@param`, `@return` and `@throws` when useful. Native Markdown Javadoc (`///`) requires JDK 23 and is not used with this JDK 21 / Java 8 toolchain.

Shared numeric settings use immutable `NumericRule` contracts for runtime YAML bounds, chat parsing, prompts and units. YAML numeric strings and decimal values for integer settings remain invalid. Cumulative inherited-price display and runtime pricing use `Prices.cumulative`; purchase limits and increasing override validation remain separate.

Prepare cloned item results before mutation, then repeat identity/context, output-space, permission and quote checks. Claim the session before charging. Compensation must prove the result is gone before returning source items or XP; keep those phases and reentrant lifecycle fences ordered.

Exclusive file creation must succeed before entering owned-file cleanup. Publish only complete backup generations and retain the newest recoverable bytes. A disk save and Bukkit publication are not one durable transaction.

## Version policy

Maven owns the release version and filters only `plugin.yml` to synchronize packaged metadata; `config.yml` stays byte-for-byte unfiltered. Increment alpha.N for complete iterations of the same planned version. Before 1.0, patches cover fixes and minors cover features or breaking configuration, command, permission or API changes. After 1.0, breaking contracts require a major. Version 1.0.0 requires explicit stable-release approval. The historical 1.0.1 exception is not retroactively renamed.

Configuration schema numbers are independent build-defined integers. Increment them only for incompatible schema changes and document the required operator adaptation.

## Native QA

`scripts\native-qa` owns the reproducible native assertion fixture and its exact-byte configuration ownership regressions. Supply a legally obtained exact Spigot or Paper 1.12.2 server JAR yourself. There are no downloads or automatic EULA acceptance. Build the production JAR first, then compile the helper with JDK 21 against the pinned Spigot API JAR (or the supplied exact server JAR containing that API):

```powershell
.\scripts\native-qa\build.ps1 -JavaHome 'C:\path\to\jdk-21' -ApiJar 'C:\path\to\spigot-api.jar' -BuildName qa-build-new
.\scripts\native-qa\test-controls.ps1 -JavaHome 'C:\path\to\java-8'
.\scripts\native-qa\run.ps1 -ServerJar 'C:\path\to\server-1.12.2.jar' -JavaHome 'C:\path\to\java-8' -FixtureJar '.playtest\qa-build-new\EnchantQA.jar' -Platform Spigot -ServerName qa-spigot-new -Port 25595 -Plan
```

The build runs seven standalone ownership scenarios and prints its helper JAR path/hash. The helper metadata derives from the current POM. Use a Java 8 runtime containing `bin\java.exe` and `release` for the Windows PowerShell runner. Review the no-write plan; replace `-Plan` with `-AcceptEula` only when explicitly authorizing acceptance of the Minecraft EULA for this local run. Repeat with `-Platform Paper`, its exact server JAR and a fresh server name/free port.

Each run creates a fresh ignored direct child of `.playtest`, binds to localhost with online authentication and RCON/query disabled, and waits for 133 behavioral assertions. It sends and flushes stdin `stop`, waits at most 90 seconds, then checks the final log and exit code. Startup and assertion timeouts fail; a shutdown timeout reports the owned PID for inspection. The runner retains its own logs and world for diagnosis and never reuses, seeds or deletes existing server directories. Configuration restoration relinquishes ownership before the summary so later edits survive teardown.

Live log reads share the server's open writer and permit rotation. The reader enforces a 16 MiB byte limit during reading; read failures propagate to graceful shutdown and a failed run.

This checks native API, metadata, permissions, reload, shelves and configuration ownership. Authenticated-player purchases, client layout and audible sound still require a separate playtest. Native QA is separate from hosted CI because the exact server binaries and local EULA authorization are caller prerequisites.

Maven wrapper Apache-2.0 notices are retained; the wrapper is tooling, not bundled plugin code.
