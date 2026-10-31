#!/usr/bin/env python3
"""Exercise the actual SDK scheduler on a JVM without Android or a real fabric."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / '.matter-sdk/connectedhomeip/src/platform/android/java/chip/platform/NsdTimeouts.java'
with tempfile.TemporaryDirectory(prefix='eluno-nsd-') as directory:
    probe = Path(directory) / 'NsdTimeoutProbe.java'
    probe.write_text('''package chip.platform;
import java.util.concurrent.*;
public class NsdTimeoutProbe {
  public static void main(String[] args) throws Exception {
    ScheduledThreadPoolExecutor executor = NsdTimeouts.executor();
    CountDownLatch finished = new CountDownLatch(1000);
    for (int i = 0; i < 1000; i++) executor.schedule(finished::countDown, 1, TimeUnit.MILLISECONDS);
    if (!finished.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timeout callbacks did not finish");
    for (int i = 0; i < 1000; i++) executor.schedule(() -> { throw new AssertionError("Cancelled callback executed"); }, 30, TimeUnit.SECONDS).cancel(false);
    if (!executor.getQueue().isEmpty()) throw new AssertionError("Cancelled callbacks retained");
    if (executor.getLargestPoolSize() != 1) throw new AssertionError("Scheduler created extra threads");
    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (executor.getPoolSize() != 0 && System.nanoTime() < limit) Thread.sleep(50);
    if (executor.getPoolSize() != 0) throw new AssertionError("Idle thread retained");
    if (executor.schedule(() -> 42, 1, TimeUnit.MILLISECONDS).get() != 42) throw new AssertionError("Scheduler cannot restart");
    executor.shutdownNow();
    System.out.println("NSD: 1000 completed + 1000 cancelled timers, peak 1 thread, idle 0 threads, restart OK");
  }
}''')
    subprocess.run(['javac', '-d', directory, str(source), str(probe)], check=True)
    subprocess.run(['java', '-cp', directory, 'chip.platform.NsdTimeoutProbe'], check=True, timeout=15)
