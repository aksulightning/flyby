# Architecture

`MainActivity -> VmService -> VmManager -> NativeVmController -> Binder -> VmSandboxService -> JNI -> RVVM`

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
`LINUX_BOOT` log identifies the guest shell or first-run password setup marker.
Waiting for the user's first password does not consume the boot timeout. Duplicate starts and
restarts during cleanup are rejected. A cleanup failure blocks reuse until retried.

Guest provisioning runs under STARTING and can be cancelled. The package's resource
hashes are checked before installing fixed filenames inside app-private storage.
Both Kotlin paths and native sizes/Image headers are validated. Configuration validates 128–768 MiB RAM and both Kotlin and native validation enforce exactly one or two CPUs.
The native boundary enforces the same limits.
No UI-supplied path, host shell, external process or broad storage permission exists.

## JNI and concurrency

JNI uses checked monotonically increasing IDs in native registries. A call takes
shared ownership, so destroying a handle cannot free memory underneath a concurrent
read. Closed/invalid handles throw Java exceptions. C++ exceptions are caught at
JNI boundaries. RVVM owns native vCPU/event-loop threads; no instructions cross JNI.
Main RAM uses RVVM native allocation in the VM worker, not the Kotlin heap. Thread shutdown joins
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
shutdown. Forced termination discards transient state and may leave the persistent
ext4 disk requiring recovery; the app never silently reformats it.

## Foreground execution

Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`,
`POST_NOTIFICATIONS`, `INTERNET`; non-exported `VmService`, `specialUse` type and the SDK-defined
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
or a five-minute boot timeout becomes an error and triggers cleanup. Extreme native allocator failures/internal core assertions may still abort the
VM worker. Binder death becomes an error in the owner; the VM is not
silently restarted.

`Vm::pause/resume` exist and are host-tested. Full process-death save/restore is not
exposed: adding snapshot persistence requires a versioned guest/device state format.

## Android VM worker

The interpreter, virtual devices and GLES/Vulkan renderers run in a non-exported
bound service with `isolatedProcess=false`, in a separate process under the app
UID. Android isolated UIDs can prevent graphics-driver access. The foreground
VmService owns the binding, wake lock, notification and terminal. Startup checks
that the worker has a different PID and the same UID; it never runs the VM in
the UI process.

Three read-only guest resource descriptors and the read/write active disk
descriptor are passed over Binder. RVVM duplicates those descriptors directly.
Serial, lifecycle, display input, 9P and Ethernet messages retain fixed limits.
Frames use pipes to avoid Binder's transaction limit, with length validation by
the owner. Unbinding terminates the worker; a crash retains the disk and reports
an error. The existing 9P server and network broker remain in the owning process.

This is process separation for lifecycle and crash handling, not a security
boundary: the worker can access private app files and internet sockets with the
app's permissions. Android's ordinary application sandbox still applies.
