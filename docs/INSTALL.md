# Installation, compatibility, and building

## Compatibility

| Requirement | Supported value |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.219 through 21.1.251 |
| Java | 21 or newer runtime supporting Minecraft 1.21.1; Java 21 target for builds |
| Mod ID | `physicalist_library` |
| Library version | 0.5.0 |
| Create, Veil, Sable, IVR | No runtime dependency in this library |

The NeoForge metadata uses `[21.1.219,21.1.252)`. A successful build on
both endpoints does not prove behavior on every intermediate release.

## For players and servers

1. Install Minecraft 1.21.1 and a supported NeoForge release.
2. Download `physicalist_library-0.5.0.jar` from the
   [GitHub releases](https://github.com/sonima-modmaker/physicalist-library/releases).
3. Put **one** copy in the instance's `mods` directory. Remove earlier
   Physicalist versions from that directory; two versions share a mod ID.
4. If you use a mod that requires the library, install its other dependencies
   too. Install the same compatible library version on client and server.
5. Launch the game and check the mod list for Physicalist Library.

In Prism Launcher, each instance has its own `minecraft/mods` directory.
The source JAR is for IDEs, not for the playable `mods` directory.

The creative tab contains the Physics Wand, Block Assembler and Physical
Entity Deleter. Hold right click with the wand to drag, scroll to change
distance, or hold Tab and move the mouse to rotate the held object. The tools
work in creative mode. The commands require
operator level 2. See [API and in-game tools](API.md#creative-tools-and-commands).

## Build from source

Use the included Gradle wrapper; do not use a random system Gradle version.

On Windows:

```powershell
.\gradlew.bat build
.\gradlew.bat build '-Pneo_version=21.1.251'
```

On Linux/macOS:

```sh
./gradlew build
./gradlew build -Pneo_version=21.1.251
```

The default build targets the lower endpoint, NeoForge 21.1.219. Its playable
output is `build/libs/physicalist_library-0.5.0.jar`. The second command
checks the upper endpoint. Both builds target Java 21 bytecode. GitHub Actions
runs the same version matrix on pushes and pull requests.

`verification/LibraryPhysicsTest.java` is a standalone calculation test for
inertia, wing detection, asymmetric torque, mass, fall drag and thin-part
collision. It uses Minecraft classes from the NeoGradle classpath and is not
a substitute for launching the game.

## Use it as a dependency in another mod

For a local NeoGradle project, put the playable JAR in `libs` and add:

```groovy
dependencies {
    implementation(files("libs/physicalist_library-0.5.0.jar"))
}
```

Declare a runtime dependency in your mod metadata:

```toml
[[dependencies.your_mod_id]]
modId="physicalist_library"
type="required"
versionRange="[0.5.0,)"
ordering="AFTER"
side="BOTH"
```

Replace `your_mod_id` with your actual ID. Compile-time inclusion alone
does not ensure the library mod is installed at runtime. There is currently
no advertised public Maven repository for this project; the template's
`publish` task writes only to a local repository folder.

The library has no built-in adapter for Immersive Vehicles Refurbished.
If you are integrating IVR or any other vehicle mod, use
[`PhysicsBody` and `PhysicalistSimulation`](API.md); adding the JAR to `mods`
does not change existing vehicles automatically.

Create: The Air War needs a build compiled against Physicalist Library
0.5.0 or later for the Sable moving-ship bridge and current wand controls.
Replacing only the library JAR cannot add that integration to an older
Create: The Air War JAR. Remove previous library JARs before launching.
