<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# vfox Integrator Changelog

## [1.0.0-alpha01] - 2026-11-26

### Changed

- The plugin is now named **vfox Integrator** (id `com.dr5hx.vfox.integrator`, detection log
  `vfox-integrator.log`). It was `vfox recognize`.

### Added

- Discovery of every SDK version installed by [vfox](https://vfox.dev): the vfox home is resolved
  from `VFOX_HOME`, `~/.vfox` or `~/.version-fox`, and SDK storage honours
  `storage.sdkPath` in `config.yaml`. Both directory shapes vfox uses are searched — most SDKs live
  under `<storage>/cache/<sdk>/` and some (golang) directly under `<storage>/<sdk>/` — so no SDK is
  missed for living in the "other" place.
- A catalogue of every plugin `vfox available` offers (44 of them), mapping each to how far the IDE
  can be wired to it: registered as an IDE SDK through a real `SdkType` (Java, Kotlin, Python),
  through a dedicated IDE model (Go, Node.js), into a build tool (Maven, Gradle), or offered through
  the environment alone (Rust, Ruby, Deno, Zig, and the rest). A plugin newer than the catalogue is
  env-only by default rather than ignored.
- Registration of discovered versions as IDE SDK candidates, each as its own entry (for example
  `vfox-java-21.0.2+14-librca`), idempotently. The `SdkType` is resolved by class name at run time,
  so the same build registers Java in IntelliJ IDEA, Python in PyCharm, and nothing where the SDK
  kind is absent — no per-product config file and no `NoClassDefFoundError`. Candidates are reduced
  to one entry per **real executable**: a project's `.vfox/sdks` link and the store directory it
  points at are the same binary, so both are not registered as two.
- A `vfox` tool window listing every discovered version, marking the active one, the version the
  current project requests, and how each SDK kind is supported, with *Rescan*, *Register as SDKs* and
  *Open Log* actions.
- Project version resolution from `.vfox.toml`, `.tool-versions`, `.node-version`, `.nvmrc` and
  `.sdkmanrc`. The project's own files are authoritative; the `vfox` CLI is consulted only when they
  declare nothing, so the global versions vfox would inherit are not mistaken for what the project
  asks for. The project's `.vfox/sdks` junctions are read too, covering a project set up with
  `vfox use` but holding no version file.
- A project's `.vfox/sdks` links are offered as candidates in their own right, with the
  project-local link path (`<project>/.vfox/sdks/<sdk>`) as the SDK home — the path the project and
  the IDE's own configuration for it use — and the machine-wide store it resolves to shown beside it
  in the tool window's *Original* column, so both paths stay visible. A link and the store version
  it points at are one executable, so they collapse to a single candidate (the link), and project
  links are ranked above the machine-wide versions: what a project selects takes precedence.
- An optional integration (off by default) that sets each **project's** own IDE SDKs to the versions
  it declares in vfox: the project SDK (its JDK), the project's Node.js runtime, and the project's Go
  SDK, each when the project declares that SDK. It is the only integration that writes a project's
  own configuration, which is why it is a setting; nothing is written for an SDK the project does not
  declare, or when the project already uses it.
- The settings page shows the **detected** vfox executable in the field rather than leaving it blank,
  so the path actually in use is visible; clearing the field keeps auto-detection, and typing a path
  overrides it.
- Every user-visible string now comes from a message bundle with a Chinese translation
  (`VfoxBundle_zh_CN.properties`), and the settings page's executable field is sized to the window
  instead of stretching across it. A language pack only translates the platform's own bundles, so
  text written into the plugin's code stayed English under a localized IDE; a bundle is what the
  language pack mechanism can actually reach.
- A declared version is resolved to one that is installed — exact, then segment prefix
  (`java = "11"` → `11.0.16+8-librca`), then the nearest release on the same `major.minor`
  (`maven = "3.9.15"` → `3.9.16`) — so a declaration that does not match an install exactly still
  binds, and Maven's home, Gradle's JVM and the terminal environment take effect.
- Build-tool integration: vfox `PATH` and variables in the built-in terminal; a vfox JDK offered to
  Gradle as a Gradle JVM; Maven's home pointed at a vfox Maven for projects that declare one; vfox
  variables added to Run/Debug configuration environments.
- A settings page under Settings → Tools → vfox SDKs for the vfox executable, the SDK kinds to
  register (offered per product, so only kinds that would do something here appear), the build-tool
  integrations to enable, and the detection feedback. Disabled kinds are stored as exceptions, so a
  newly catalogued SDK is registered by default without a settings migration.
- Detection feedback: a balloon summarising each scan that found SDKs, and a dedicated
  `vfox-integrator.log` beside the IDE's other logs recording every scan in full (one line per
  version, with its resolved home and environment variables). Both are on by default and can be
  turned off; the log is also reachable from the tool window's *Open Log* action.
- The tool window fills itself in as soon as a scan finishes, rather than staying on "not scanned
  yet" until the user rescans: a completed scan is published on `VfoxService.SCAN_COMPLETED`, which
  the window subscribes to, so it does not matter whether the start-up scan lands before or after the
  window is built. The start-up scan also now runs even when every SDK-registration toggle is off —
  those toggles decide what is registered, not whether vfox is read, and skipping the scan had left
  the window empty and starved the build-tool integrations. Tool-window columns are sized to their
  content (the table scrolls sideways) instead of being squeezed equally into the window's width,
  which had clipped every header to two characters; a long path is cut short in the cell with the
  whole value shown as a tooltip.
- Cross-product support: each integration loads only where its bundled plugin is present, so one
  build runs in IntelliJ IDEA, WebStorm, PyCharm, GoLand and DataGrip. On start-up the plugin scans
  vfox in the background.
