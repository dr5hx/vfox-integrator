# vfox Integrator

Source: <https://github.com/dr5hx/vfox-integrator> — releases:
<https://github.com/dr5hx/vfox-integrator/releases>

An IntelliJ Platform plugin that detects SDKs managed by [vfox](https://vfox.dev) and offers them as
selectable SDK candidates in the IDE, so vfox paths no longer have to be registered by hand.

vfox manages dozens of toolchains (java, nodejs, python, golang, kotlin, maven, gradle, bun, rust, …)
with one command. This plugin reads what vfox has installed and surfaces it where the IDE expects
SDKs:

| Ecosystem | Where it appears |
|---|---|
| Java | Project Structure → SDKs (Java SDK), Gradle **Gradle JVM** combo |
| Kotlin | Project Structure → SDKs (Kotlin SDK) |
| Python | Project Structure → SDKs (Python SDK) |
| Go | Settings → Go → GOROOT list |
| Node.js | Settings → Languages & Frameworks → Node.js interpreter combo |
| Maven / Gradle | Maven home; Gradle **Gradle JVM** combo |
| Everything else | The vfox tool window and the terminal/run environments |

Coverage is driven by one catalogue ([`VfoxSdkCatalog`](src/main/kotlin/com/dr5hx/vfox/inject/VfoxSdkCatalog.kt))
that maps each vfox plugin to how far the IDE can be wired to it. Three levels exist:

- **SDK candidate** — registered in the IDE's SDK table through a real `SdkType` (Java, Kotlin,
  Python). Which `SdkType` is chosen is a class name resolved at run time, so the integration
  degrades to nothing in a product that lacks it instead of failing to load.
- **Dedicated** — registered through the IDE's own model where that model is not an `SdkType` (Go's
  `GoSdkList`, Node's interpreter list).
- **Build tool** — wired into a build tool rather than the SDK table (Maven home, Gradle JVM).
- **Env only** — a vfox plugin with no IDE SDK model (Rust, Ruby, Deno, Zig, …) is still listed in
  the tool window and exported into `PATH`/variables, so its tooling is on `PATH` inside the IDE even
  though there is no SDK to pick.

`vfox available` lists 44 plugins; every one is in the catalogue, so adding support for a new one
that maps onto an existing `SdkType` is a single row.

It runs in IntelliJ IDEA, WebStorm, PyCharm, GoLand, DataGrip and other IntelliJ Platform products.
Each integration is enabled only where the bundled plugin it needs is present: the same build loads in
WebStorm (no Java plugin) and PyCharm (no Java, no Go) without failing.

## What it does and does not do

The plugin **offers candidates**; it does not silently rewrite a project's configuration.

- Every installed vfox version is registered as a separate candidate (`vfox-java-21.0.2+14-librca`).
- A project declaring versions in `.vfox.toml` / `.tool-versions` / `.node-version` / `.nvmrc` /
  `.sdkmanrc` (or leaving its selection in `.vfox/sdks`) has the matching version marked in the tool
  window's list, resolved to an installed release where the declaration does not match one exactly.
- A project's `.vfox/sdks` links are listed as their own candidates, with the project-local link path
  as the home and the store it resolves to shown beside it, so both paths stay visible. A link and
  the store it points at are the same executable, so they collapse to one candidate, with project
  links listed above the machine-wide versions — what a project selects outranks the global store.
- Registration is idempotent — repeated scans never duplicate entries.
- Choosing an SDK stays a user action; the Gradle JVM resolver only *resolves* the name it is asked
  about and never writes the project's Gradle JVM setting.
- The scan result reaches the tool window whichever side of it finishes first: the start-up scan runs
  in the background while the window is being built, so a window built second subscribes to
  `VfoxService.SCAN_COMPLETED` and refreshes itself rather than showing "not scanned yet" until the
  user rescans by hand.

Build-tool integrations that change how builds run (terminal, Gradle JVM, Maven home, Run
configurations) are **off by default** except the terminal; so is setting each project's own IDE
SDKs. Turn them on in Settings → Tools → vfox SDKs.

## Requirements

- An IntelliJ Platform IDE, build 262 or newer.
- A vfox installation. It does not have to be on the IDE's `PATH` — if the IDE was launched from a
  desktop shortcut it usually will not be, so point the plugin at the executable under
  Settings → Tools → vfox SDKs (or leave it blank and let auto-detection look on `PATH`, then in
  the conventional install locations). The vfox *home* needs no setting: it is found from
  `VFOX_HOME`, `~/.vfox` or `~/.version-fox`.

vfox's own CLI is preferred but not required: each version is enriched with its real home and
environment via `vfox info <sdk>@<version> --json`, and when the CLI is unavailable the plugin falls
back to reading the SDK directories on disk.

## Usage

1. **Settings → Tools → vfox SDKs** — set the vfox executable only if it is not on the IDE's `PATH`;
   choose which SDK kinds to register, and enable the build-tool integrations you want.
2. **The `vfox` tool window** (right dock) — lists every discovered version, marks the active one,
   the version the current project requests, whether each row is a machine-wide version or one of the
   project's own `.vfox/sdks` links, and how each SDK kind is supported (*SDK*, *build tool* or *env
   only*). For a project link, the *Original* column shows the store path it resolves to. Columns are
   sized to their content and the table scrolls sideways rather than squeezing eight columns into the
   tool window's width — a long path is cut short in the cell and shown in full as a tooltip.
   *Rescan* re-reads vfox; *Register as SDKs* adds the candidates; *Open Log* opens the detection log.
   The list fills itself in when the start-up scan lands, without a manual rescan.
3. The SDKs then appear in the usual dropdowns — no restart needed.

Once a scan detects SDKs the plugin shows one balloon summarising what it found (for example
`java (5), nodejs (6), python (1)`) and records the full result in its own log file. Both are
controlled from the same settings page; the log is also reachable from the *Open Log* button in the
tool window.

The tool window and settings page are the only UI; the plugin adds no editor actions.

Every user-visible string comes from `messages/VfoxBundle.properties`, so the plugin follows the
IDE's own language. A language pack translates the *platform's* bundles by shipping files under their
paths; it cannot reach into a third-party plugin, so a string compiled into the plugin's code stays
English whatever language the IDE runs in. A bundle can be translated, because the platform looks for
`VfoxBundle_<locale>.properties` beside the default one — `VfoxBundle_zh_CN.properties` is included,
and `VfoxBundleTest` fails if it ever misses a key or stops decoding as UTF-8.

## Detection feedback

Two things tell you what the plugin saw, and both are on by default:

| Mechanism | What it shows | Where |
|---|---|---|
| Notification | One balloon per scan that found SDKs, e.g. `vfox detected 12 SDK version(s)` with a per-SDK count | IDE balloon, and `idea.log` (the notification group is `logByDefault`) |
| Log file | Every scan in full: one line per version with its SDK, version, active flag, resolved home and environment variable names | `<IDE log dir>/vfox-integrator.log` |

The log is separate from `idea.log` on purpose — it is small, it is never rotated mid-diagnosis, and
it holds only vfox's own output, which makes "it stopped finding Java" answerable at a glance. Its
path is shown on the settings page and *Open Log* in the tool window opens it in an editor.

Both are best-effort: a missing notification group or a read-only log directory is logged at debug
level and otherwise ignored, so neither can break a scan.

## How it finds things

- **vfox home:** `VFOX_HOME`, else `~/.vfox`, else `~/.version-fox`. Not configurable — it is always
  discoverable, so the only path worth a setting is the executable.
- **vfox executable:** a path set in settings, else `PATH`, else the conventional install locations
  (`~/.vfox/bin`, scoop's shims).
- **SDK storage:** `storage.sdkPath` from `<home>/config.yaml` when set (common on Windows, where
  the home is on `C:` but SDKs live elsewhere), else `<home>/sdks`.
- **Versions:** the `v-<version>` directories under each SDK, newest first. The SDK directory is
  located in **both** shapes vfox uses — `<storage>/cache/<sdk>/` (most SDKs) and
  `<storage>/<sdk>/` (golang, for instance) — because a scanner that checks only one of them finds
  only the SDKs that happen to live there.
- **Authoritative details:** `vfox info <sdk>@<version> --json` for home and environment variables;
  `vfox current` for the active version.

## What a project asks for

A project's own version files are the authority, read upwards from the project directory:
`.vfox.toml` (`[tools]`), `.tool-versions`, and the legacy `.node-version` / `.nvmrc` / `.sdkmanrc`
vfox is configured to honour. The `vfox` CLI is consulted **only when those files declare nothing**,
because the CLI also reports *inherited global* versions — a project with a Go-only `.vfox.toml`
would otherwise be told it wants the machine's global Node.js. The project's own `.vfox/sdks` links
are the last resort, consulted only when neither the files nor the CLI answer.

A declared version is not always installed exactly (`java = "11"` on a machine holding
`11.0.16+8-librca`). Each requirement is resolved against what is installed, most exact first: the
exact version, then a segment prefix (`11` → `11.0.16+8-librca`), then the nearest release on the
same `major.minor` line (`3.9.15` → `3.9.16`). A project's `.vfox/sdks` link is read too, but only
as a *source* of requirements when neither the files nor the CLI declare anything — it records the
version vfox last selected and goes stale the moment the declaration changes, so it never overrides
a declaration.

### The project's own `.vfox/sdks` links

When a project selects a version with `vfox use`, vfox records it as a directory link at
`<project>/.vfox/sdks/<sdk>`, pointing into the machine-wide store. The plugin lists that link as a
candidate **in its own right**, with the link path as the SDK home and the store it resolves to
shown beside it in the tool window's *Original* column. This matters because the link is the path
the project — and the IDE's own configuration for it, such as the Node.js interpreter — actually
uses: WebStorm records `$PROJECT_DIR$/.vfox/sdks/nodejs/node`, not the store path.

A link and the store directory it points at are the **same executable** under two names, and the
IDE's SDK tables compare paths literally — so the plugin reduces candidates to one entry per real
executable. The link survives (the path the project actually uses) and the store path stays visible
in the *Original* column. Without this the IDE would list the same Node twice. Project links then
rank **first**: what a project selected sits above the machine-wide store, and the ordering is
stable, so the newest-first version order is kept inside each group.

### What a project declares

The IDE's SDK settings default to whatever was chosen before — often the machine-wide one — so a
project whose `.vfox.toml` says `nodejs = "18.20.8"` or `java = "11"` still runs on the global
Node and another JDK, even though the right ones are registered and selectable. Settings → Tools →
vfox SDKs has an option, **off by default**, that sets each project's own IDE SDKs to the versions
it declares in vfox:

| What the project declares | What is set |
|---|---|
| `java` (or `kotlin` / `python`) | the project's **project SDK** |
| `nodejs` | the project's **Node.js interpreter** |
| `golang` | the project's **Go SDK** |

Whatever the project declares wins, and nothing is written for an SDK it does not declare, or when
it already uses the right one. The value written is the project's own `.vfox/sdks/<sdk>` link when
it has one (that is the path the IDE records), else the declared version bound to the installed
release — the same resolution the tool window shows.

It is the only integration that writes a project's own configuration, which is why it is opt-in;
every other integration only offers candidates. A project that declares several of these has its
project-SDK slot given to Java first, then Python, then Kotlin, since there is only one such slot.

Resolution is what makes the build-tool integrations work: Maven's home, Gradle's JVM and the
terminal environment bind the *resolved* version, so a declaration that does not match an install
byte-for-byte still takes effect instead of silently doing nothing. The tool window shows both
(`java@11 -> 11.0.16+8-librca`), and an unmatched declaration is shown as declared, unresolved.

## Building

```shell
./gradlew build          # compile, test, package
./gradlew test           # unit tests
./gradlew runIde         # launch a sandbox IDE with the plugin installed
./gradlew verifyPlugin   # check compatibility against the configured IDEs
```

Running `runIde` leaves a sandbox IDE that keeps its plugin jars memory-mapped, so a later
`prepareSandbox*` copy fails on Windows with *"The requested operation cannot be performed on a file
with a user-mapped section open"* — which reads like a corrupt Gradle cache but is only the leftover
process. Each `prepareSandbox*` task therefore depends on a `killLeftoverSandbox*` task that stops any
IDE still running out of this project's sandbox first, found through the `.pid` file the IDE writes
under `system*/` (matching `idea.plugins.path` out of the command line does not work on Windows,
where the JDK returns an empty command line for nearly every process). Nothing outside this project's
sandbox is touched, so an unrelated IDE the user has open is never affected.

The project compiles against IntelliJ IDEA Ultimate because it is the only edition bundling every
SDK ecosystem it integrates with; Python and Go are resolved as marketplace plugins pinned per
platform build.

## Publishing to JetBrains Marketplace

Two things are needed beyond the source: a **signing key pair**, which identifies the plugin author,
and a **Marketplace token**, which authorizes the upload. Neither belongs in this repository.

### Prepare once: the signing key pair

```powershell
pwsh -File scripts/generate-signing-key.ps1
```

It writes `chain.crt`, `private.pem` and `password.txt` to `~/.vfox-integrator-signing` (outside the
working tree; `.gitignore` also blocks `*.pem`/`*.crt` as a backstop). **Keep the key**: it is the
author's identity — a future release signed with a different key does not chain to this one, so
losing it means starting the trust chain over. `-OpenSslPath` overrides openssl detection when
nothing puts it on `PATH` (Git for Windows ships one, and the script finds it via `git --exec-path`).

### Prepare once: the Marketplace account and first upload

The **first version of a plugin must be uploaded by hand** — JetBrains does not accept it through
the Gradle task. In the browser:

1. Sign in at <https://plugins.jetbrains.com> with a JetBrains Account (create one if needed).
2. **Add new plugin**, and give it the id `com.dr5hx.vfox.integrator` — it must match `plugin.xml`
   exactly, because the id is what later uploads are matched against.
3. Add a **license** (already in the repository as [LICENSE](LICENSE), MIT) and the vendor details.
4. Upload `build/distributions/vfox-integrator-1.0.0-alpha01-signed.zip` (see below for building it
   signed). Verify that the *uploaded* archive is the signed one.

After that first upload, later versions go through Gradle.

### Each release

```powershell
# 1. Bump `version` in gradle.properties and add a CHANGELOG.md entry — the Marketplace rejects a
#    version it has already seen, and the changelog entry becomes the release's change notes.
# 2. Publish. PUBLISH_TOKEN is prompted for if it is not already in the environment.
pwsh -File scripts/publish.ps1                    # channel: default (Stable), what users see
pwsh -File scripts/publish.ps1 -Channel alpha     # a pre-release on its own channel
pwsh -File scripts/publish.ps1 -DryRun            # build and sign, upload nothing
```

The channel must match the one chosen on the plugin's Marketplace page. It defaults to `default` —
the Stable channel every user sees — so the plugin stays installable by anyone; an `alpha` version
number does not by itself require a custom channel. A non-default channel is a *separate
repository*: an `alpha` upload reaches only users who added
`https://plugins.jetbrains.com/plugins/alpha/list`, and because a custom repository takes precedence
over the default one, those users stop receiving stable updates while subscribed. The channel comes
from `-Channel` / the `vfoxPublishChannel` Gradle property.

The underlying Gradle tasks, if you prefer to drive them yourself:

| Task | What it does |
|---|---|
| `buildPlugin` | packages the distribution (unsigned) |
| `signPlugin` | signs it, producing `*-signed.zip`; runs automatically before `publishPlugin` |
| `verifyPluginSignature` | checks a signature against the certificate chain |
| `publishPlugin` | uploads to the Marketplace (`patchChangelog` first) |

Secrets reach the tasks through the environment — `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN` — which is also what a CI job supplies from its own secret
store. No key material is ever written into a file inside the repository.

## Project layout

```
src/main/kotlin/com/dr5hx/vfox/
├── model/          Plain data: SDK, version, scan result
├── core/           vfox discovery: home locator, CLI runner, scanner, project resolver, JSON
├── inject/         SDK registration: the catalogue, the generic SdkType injector, the candidate
│                   reduction, the project-initializer extension point, Go/Node adapters
├── buildtools/     Terminal / Gradle JVM / Maven home / Run-configuration integration
├── settings/       Persistent settings and their settings page
├── ui/             The vfox tool window
├── VfoxBundle.kt   The localized message accessor for every user-visible string
└── VfoxStartupActivity.kt
src/main/resources/META-INF/
├── plugin.xml          Core plugin descriptor
├── pluginIcon.svg      Plugin logo, light themes
├── pluginIcon_dark.svg ... dark themes
├── vfox-java.xml       Merged only where the Java plugin is present (Run/Debug env hook)
├── vfox-node.xml       ... JavaScript plugin (Node interpreter injector, project Node default)
├── vfox-go.xml         ... Go plugin (Go SDK injector, project Go SDK)
├── vfox-terminal.xml   ... Terminal plugin
├── vfox-gradle.xml     ... Gradle plugin
└── vfox-maven.xml      ... Maven plugin
src/main/resources/messages/
├── VfoxBundle.properties        English (the default)
└── VfoxBundle_zh_CN.properties  Chinese; chosen by the IDE's own language setting
scripts/
├── generate-signing-key.ps1   Creates the Marketplace signing key pair (outside the repo)
└── publish.ps1                Signs and uploads to the Marketplace
```

The per-product config files are what make the plugin cross-product: a file that is never merged
means its classes are never loaded, so a reference to a bundled plugin that a product lacks (the
Java plugin in WebStorm, for instance) can never cause a `NoClassDefFoundError`.

Java, Kotlin and Python do **not** need such a file: their integration is the generic
`SdkTypeInjector`, which finds its `SdkType` by class name at run time and quietly registers nothing
where the class is absent. Only integrations that reference a bundled plugin's API directly — Go's
`GoSdkList`, Node's `NodeJsLocalInterpreterManager` / `NodeJsInterpreterManager`, the Run/Debug
extension — live in a per-product file.

An integration that has to act on the **project** once SDKs are registered (not just offer them) is
contributed through the `com.dr5hx.vfox.projectInitializer` extension point, which
`VfoxStartupActivity` runs after registration. That ordering is the point: pointing a project's
Node.js interpreter at the version it declares depends on the interpreter having been registered
first, so it cannot be a start-up activity of its own without racing the scan.

## Notes on internal APIs

Several integrations use APIs that carry no compatibility guarantee — `GradleJvmResolver` is
`@ApiStatus.Experimental`, and `MavenProjectsManager` / `NodeJsLocalInterpreterManager` / `GoSdkList`
/ `PythonSdkType` are bundled-plugin internals. Each is isolated behind an interface, guarded by a
settings toggle, and wrapped so a failure is logged and skipped rather than allowed to break the scan
or the IDE. `./gradlew verifyPlugin` reports any that drift.
