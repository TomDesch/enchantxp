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

## Source map

Under `io.github.stealingdapenta.enchantxp`, only `EnchantXpPlugin` lives in the root and wires lifecycle, listeners and commands.

- `administration`: editor drafts, page definitions, serialized reload/save and file ownership. Drafts and save tickets stay together, package-private.
- `menu`: events, session identity, quotes and transactions. `MenuRenderer` builds unpublished views using immutable route/pagination values; `TableMenus` owns publication and lifecycle fences.
- `configuration`: immutable validated settings; `configuration.values` provides strict optional/required YAML readers and fresh packaged defaults.
- `presentation`: immutable text/sound settings, shared explicit lore roles and menu stages. Lore-style settings live outside `ui` to preserve its existing omitted-lore contract.
- `enchanting`: enchantment rules, cumulative prices, shelves, permissions and raw XP. Menu admission stays package-private with its purchase workflow.

Dependencies flow from administration to menu/configuration, from menu to configuration/presentation/enchanting, and from configuration to presentation/enchanting. Presentation uses enchanting names; both depend on shared YAML readers below them. Rules and presentation never depend on controllers. Tests mirror these boundaries; reusable YAML and metadata fixtures live in `testsupport`.

Prepare cloned item results before mutation, then repeat identity/context, output-space, permission and quote checks. Claim the session before charging. Compensation must prove the result is gone before returning source items or XP; keep those phases and reentrant lifecycle fences ordered.

Exclusive file creation must succeed before entering owned-file cleanup. Publish only complete backup generations and retain the newest recoverable bytes. A disk save and Bukkit publication are not one durable transaction.

## Version policy

Keep Maven and plugin metadata synchronized. Increment alpha.N for complete iterations of the same planned version. Before 1.0, patches cover fixes and minors cover features or breaking configuration, command, permission or API changes. After 1.0, breaking contracts require a major. Version 1.0.0 requires explicit stable-release approval. The historical 1.0.1 exception is not retroactively renamed.

Configuration schema numbers are independent build-defined integers. Increment them only for incompatible schema changes and document the required operator adaptation.

Maven wrapper Apache-2.0 notices are retained; the wrapper is tooling, not bundled plugin code.
