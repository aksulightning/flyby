# Architecture

`MainActivity (bind/unbind) -> VmService -> VmManager -> NativeVmController -> JNI -> RVVM`

`VmService` owns the coroutine scope, manager, native runtime and `TerminalEmulator`.
A binding is only a view onto that session. The explicit Start intent starts the
foreground service before provisioning files or allocating RAM. Unbinding an
Activity does not stop a started service. Stop/error removes the notification,
releases the wake lock and calls stopSelf; a bound UI can still inspect its terminal.
START_NOT_STICKY avoids silently substituting a fresh VM after process death.

The Activity observes `StateFlow<VmStatus>`. `TerminalSession` hides runtime handles
from UI. `VmManager` retains the Phase 1 mutex/cancellation/cleanup state machine:
STOPPED/ERROR -> STARTING -> RUNNING -> STOPPING -> STOPPED, with failures -> ERROR.
RUNNING means native execution has started, not that Linux finished booting. The
`LINUX_BOOT` log identifies the actual guest shell marker. Duplicate starts and
restarts during cleanup are rejected. A cleanup failure blocks reuse until retried.

Guest provisioning runs under STARTING and can be cancelled. The package's resource
hashes are checked before installing fixed filenames inside app-private storage.
Both Kotlin paths and native sizes/Image headers are validated. Configuration has
future broad bounds but `validateRuntime` enforces current 256–1024 MiB / one CPU.
No UI-supplied path, host shell, external process or broad storage permission exists.

## JNI and concurrency

JNI uses checked monotonically increasing IDs in native registries. A call takes
shared ownership, so destroying a handle cannot free memory underneath a concurrent
read. Closed/invalid handles throw Java exceptions. C++ exceptions are caught at
JNI boundaries. RVVM owns native vCPU/event-loop threads; no instructions cross JNI.
Main RAM uses RVVM native allocation, not the Kotlin heap. Thread shutdown joins
before RAM/UART objects are freed. ART retains signal handling; RVVM crash signal
handlers, JIT, KVM, GUI, VFIO and host isolation are disabled.

The UART callback uses preallocated bounded queues (64 KiB input, 1 MiB output),
never per-byte JNI callbacks. The controller waits on a condition variable and
coalesces output for approximately 16 ms. WFI sleeps using RVVM's timer/condition
variable implementation. libvterm processes each batch under its own lock. UI gets
screen cells and invalidates once per batch. A 32,768-character diagnostic transcript
and 2,000-line terminal history survive Activity recreation in the service.

The second UART waits for the guest control daemon's READY handshake before
exposing queued input. This prevents Linux's UART initialization from discarding
an early resize or shutdown. It carries only fixed Stop and numeric resize requests;
management never writes into the user's shell. Guest shutdown is followed by native
cleanup. After ten seconds, cancellation falls back to stop/join without a guest
shutdown. With the current RAM filesystem this discards transient state.

## Foreground execution

Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`,
`POST_NOTIFICATIONS`; non-exported `VmService`, `specialUse` type and the SDK-defined
`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Start originates from a visible
Activity. Notification opens Flyby and provides Stop. A partial wake lock lasts
only as long as active execution/startup/cleanup. Normal Activity lifecycle does
not own these resources. Android can still terminate the process. Play distribution
would require review of the declared special-use case; approval is not claimed.

Sources: [service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[Android 14 declarations](https://developer.android.com/about/versions/14/changes/fgs-types-required).

## Diagnostics and errors

`FlybyVM`: VM_CREATE, VM_START, VM_STOP, VM_EXIT, NATIVE_EXIT_CODE, LINUX_BOOT,
CONSOLE_CONNECTED, NATIVE_ERROR. `FlybyRVVM` receives upstream native diagnostics
through an Android-only CMake logging adapter. Input payloads are not logged.
Missing resources, invalid images, native RAM allocation failure and startup
exceptions become ERROR. The terminal retains boot output; detected kernel panic
or a five-minute boot timeout becomes an error and triggers cleanup. Extreme native
allocator failures/internal core assertions may still abort in this in-process
architecture; they are a limitation, not normal error handling.

`Vm::pause/resume` exist and are host-tested. Full process-death save/restore is not
exposed: adding snapshot persistence requires a versioned guest/device state format.
