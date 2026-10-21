package io.github.aksulightning.flyby.sandbox;
import android.os.ParcelFileDescriptor;
// Bounded control/data messages only. The worker never receives a host pathname,
// a ContentResolver, settings, a general-purpose file opener or a command runner.
interface IVmSandbox {
    int[] identity();
    String vulkanSupport();
    boolean canReadPrivateFile(String path);
    boolean canOpenInternetSocket();
    long createVm(in ParcelFileDescriptor[] resources, int ram, int cpus, boolean system, boolean shared, boolean gpu, boolean vulkan);
    void startVm(long id);
    boolean runningVm(long id);
    void requestStopVm(long id);
    void inputVm(long id, in byte[] data);
    byte[] readVm(long id, int timeoutMs);
    void sharedInputVm(long id, in byte[] data);
    byte[] sharedOutputVm(long id);
    void resizeVm(long id, int rows, int cols);
    ParcelFileDescriptor displayFrame(long id);
    boolean displayInputVm(long id, in byte[] reports);
    byte[] networkOutputVm(long id);
    void networkInputVm(long id, in byte[] data);
    void terminate();
}
