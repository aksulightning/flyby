# Phase 1 architecture

`MainActivity` renders Compose `MainScreen` or `TerminalScreen`. `FlybyApplication`
owns one `VmManager`, its coroutine scope and its `StateFlow<VmStatus>`, so rotation
does not create another manager. UI observes flows with lifecycle-aware collection.
This ownership preserves state across Activity recreation **only**. It is not a
substitute for a foreground service or protection against Android process death.

`VmManager` implements `TerminalSession`. UI sees bounded diagnostic output and
the terminal input contract; it never sees a `Process`, file descriptor or shell.
The transcript is limited to 32,768 UTF-16 code units and handles UTF-8 sequences
split across chunks. It is explicitly not an ANSI terminal emulator.

`QemuCommandBuilder` validates config and files, then returns a `List<String>`.
Paths are canonicalized, checked for root containment (including symlinks and
prefix siblings), and required to be readable, nonempty files. Executables must
be under the package-installed native directory. Application-owned roots are
trusted inputs; never accept these roots from future settings/import UI. A future
import must copy via SAF into private storage, then validate the copied file.

RAM defaults to 512 MiB, allowed range 128–2048. CPUs default to 1, range 1–4.
No user-controlled free-form QEMU flags exist. Terminal input is never a command
line argument and never passes through a host shell.

## State machine

| Current state | Action/event | Next state |
| --- | --- | --- |
| STOPPED or ERROR | Start, no active job | STARTING |
| STARTING | Validation/spawn failure | ERROR |
| STARTING | Controller confirms spawn | RUNNING |
| STARTING or RUNNING | Stop | STOPPING |
| RUNNING | Exit 0 | STOPPED |
| RUNNING | Exit nonzero | ERROR |
| STOPPING | Exit or cancellation cleanup | STOPPED |
| Any active state | Cleanup fails | ERROR |

Starts are serialized by a mutex. An active job rejects duplicate Start. Stop
during startup cancels startup and waits for cleanup; Stop while running asks
the controller for shutdown, then falls back to cancellation/force-stop after
five seconds. Final state publication happens after cleanup.

## Process boundary: not connected yet

`QemuController` specifies cancellation-safe spawn, output delivery, suspending
exit waiting, serial input, graceful stop and force-stop/reaping. Tests inject a
fake **only in test sources**. Production has no controller in Phase 1: Start
reports the missing QEMU executable; even manually installing files cannot make
the app claim a VM is running. No `Hello from Linux` message is synthesized.

The next controller must use `ProcessBuilder(arguments)` with separate readers
for stdout and stderr on IO dispatchers, and blocking `waitFor` on IO (not busy
polling). It must not return from cleanup with a live child. Use a dedicated QMP
Unix socket for guest `system_powerdown`, followed by timed process termination;
do not inject `poweroff` into a user's shell. The current CLI intentionally has
no QMP endpoint until that controller is implemented and tested.

Before wiring a controller to UI, add a started foreground service with a
notification channel and Stop action. At target 35, declare the real
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` permissions, the service
type `specialUse`, and `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` describing
the user-started Linux VM. Start it from the visible Activity, enter foreground
immediately, and stop it after reaping the VM. This is a proposed fit for the
use case, not an assertion of Google Play approval. Do not use `dataSync` to
sidestep service duration rules. Decide restart behavior explicitly; the MVP
should not silently restart a guest after host process death.

## Logs

Manager events use logcat tag `FlybyVM`: `VM_START`, `VM_STOP`, `VM_EXIT`,
`QEMU_EXIT_CODE`. Phase 2 must add `QEMU_STDERR` from its dedicated diagnostic
reader. Input payloads are never logged. A controller implementation must bound
any diagnostic buffer and not interpret stderr as guest terminal escape sequences.

## Later phases

After Android ARM64 spawn and `Hello from Linux` boot pass, implement terminal
emulation, key modifiers, resize propagation and copy/paste. Select and identify
the license of the terminal library before adding it; no Termux dependency exists.
Only then add persistent block storage, outbound user networking and settings.

Sources:
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 14 service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
