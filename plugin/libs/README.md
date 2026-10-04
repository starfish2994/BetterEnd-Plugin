# `plugin/libs`

## `UltimateAdvancementAPI-Plugin-2.8.0.jar`

`plugin/build.gradle.kts` compiles against this jar with

```kotlin
compileOnly(files("libs/UltimateAdvancementAPI-Plugin-2.8.0.jar"))
```

so a fresh clone needs the file present to build. It is **not** shaded into the plugin jar and is
**not** required at runtime; the plugin checks that the server has the plugin installed before it
touches any of its classes, so a server without it loads BetterEnd minus the advancement tab.

The jar is the owner's fork of
[UltimateAdvancementAPI](https://github.com/frengor/UltimateAdvancementAPI) 2.8.0/2.8.1 and adds
MC 26.2 NMS support, Folia-aware packet dispatch and the auto-layout `registerAdvancements`
overload. It is licensed **LGPL-3.0-or-later**, Copyright (C) 2021 fren_gor, EscanorTargaryen; the
license text and `NOTICE` are inside the jar. See [../THIRD-PARTY.md](../THIRD-PARTY.md).

If you would rather not use this fork:

1. Drop the `compileOnly(files(...))` line from `plugin/build.gradle.kts`.
2. Remove the four `advancement/` classes from `plugin/src/main/java` that reference it, or guard
   them behind your own API version.
3. Remove the `UltimateAdvancementAPI` entry from `plugin/src/main/resources/paper-plugin.yml`.

`advancements.enabled` in `config.yml` is already a no-op when the plugin is absent.
