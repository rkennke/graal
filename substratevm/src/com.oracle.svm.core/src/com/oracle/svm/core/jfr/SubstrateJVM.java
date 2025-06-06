/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jfr;

import java.util.List;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.jfr.events.JfrAllocationEvents;
import com.oracle.svm.core.jfr.logging.JfrLogging;
import com.oracle.svm.core.jfr.oldobject.JfrOldObjectProfiler;
import com.oracle.svm.core.jfr.oldobject.JfrOldObjectRepository;
import com.oracle.svm.core.jfr.sampler.JfrExecutionSampler;
import com.oracle.svm.core.jfr.throttling.JfrEventThrottling;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.sampler.SamplerBufferPool;
import com.oracle.svm.core.sampler.SamplerBuffersAccess;
import com.oracle.svm.core.sampler.SamplerStatistics;
import com.oracle.svm.core.sampler.SubstrateSigprofHandler;
import com.oracle.svm.core.thread.JavaThreads;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.word.Word;
import jdk.internal.event.Event;
import jdk.jfr.Configuration;
import jdk.jfr.internal.JVM;
import jdk.jfr.internal.LogTag;

/**
 * Manager class that handles most JFR Java API, see {@link Target_jdk_jfr_internal_JVM}.
 * <p>
 * Here is the execution order of relevant API methods:
 * <ul>
 * <li>{@link #createJFR} - initialize the JFR infrastructure but don't record any events yet.</li>
 * <li>{@link #setOutput} - set the path of the file where the JFR data should be written to.</li>
 * <li>{@link #beginRecording} - start recording JFR events.</li>
 * <li>{@link #setOutput} - either switch to a new file or close the current file.</li>
 * <li>{@link #endRecording()} - end recording JFR events.</li>
 * <li>{@link #destroyJFR()} - destroy the JFR infrastructure and free data.</li>
 * </ul>
 */
public class SubstrateJVM {
    private final List<Configuration> knownConfigurations;
    private final JfrOptionSet options;
    private final JfrNativeEventSetting[] eventSettings;

    private final JfrSymbolRepository symbolRepo;
    private final JfrTypeRepository typeRepo;
    private final JfrThreadRepository threadRepo;
    private final JfrStackTraceRepository stackTraceRepo;
    private final JfrMethodRepository methodRepo;
    private final JfrOldObjectRepository oldObjectRepo;

    private final JfrThreadLocal threadLocal;
    private final JfrGlobalMemory globalMemory;
    private final SamplerBufferPool samplerBufferPool;
    private final JfrUnlockedChunkWriter unlockedChunkWriter;
    private final JfrRecorderThread recorderThread;
    private final JfrOldObjectProfiler oldObjectProfiler;

    private final JfrLogging jfrLogging;
    private final JfrEventThrottling eventThrottler;

    private boolean initialized;
    /*
     * We need this separate field for all JDK versions, i.e., even for versions where the field
     * JVM.recording is present (JVM.recording is not set for all the cases that we are interested
     * in).
     */
    private volatile boolean recording;
    private String dumpPath;

    @Platforms(Platform.HOSTED_ONLY.class)
    public SubstrateJVM(List<Configuration> configurations, boolean writeFile) {
        this.knownConfigurations = configurations;

        options = new JfrOptionSet();

        int eventCount = JfrMetadataTypeLibrary.getPlatformEventCount();
        eventSettings = new JfrNativeEventSetting[eventCount];
        for (int i = 0; i < eventSettings.length; i++) {
            eventSettings[i] = new JfrNativeEventSetting();
        }

        symbolRepo = new JfrSymbolRepository();
        typeRepo = new JfrTypeRepository();
        threadRepo = new JfrThreadRepository();
        stackTraceRepo = new JfrStackTraceRepository();
        methodRepo = new JfrMethodRepository();
        oldObjectRepo = new JfrOldObjectRepository();

        threadLocal = new JfrThreadLocal();
        globalMemory = new JfrGlobalMemory();
        samplerBufferPool = new SamplerBufferPool();
        unlockedChunkWriter = writeFile ? new JfrChunkFileWriter(globalMemory, stackTraceRepo, methodRepo, typeRepo, symbolRepo, threadRepo, oldObjectRepo) : new JfrChunkNoWriter();
        recorderThread = new JfrRecorderThread(globalMemory, unlockedChunkWriter);
        oldObjectProfiler = new JfrOldObjectProfiler();

        jfrLogging = new JfrLogging();
        eventThrottler = new JfrEventThrottling();

        initialized = false;
        recording = false;
    }

    @Fold
    public static SubstrateJVM get() {
        return ImageSingletons.lookup(SubstrateJVM.class);
    }

    @Fold
    public static List<Configuration> getKnownConfigurations() {
        return get().knownConfigurations;
    }

    @Fold
    public static JfrGlobalMemory getGlobalMemory() {
        return get().globalMemory;
    }

    @Fold
    public static JfrRecorderThread getRecorderThread() {
        return get().recorderThread;
    }

    @Fold
    public static JfrThreadLocal getThreadLocal() {
        return get().threadLocal;
    }

    @Fold
    public static SamplerBufferPool getSamplerBufferPool() {
        return get().samplerBufferPool;
    }

    @Fold
    public static JfrUnlockedChunkWriter getChunkWriter() {
        return get().unlockedChunkWriter;
    }

    @Fold
    public static JfrTypeRepository getTypeRepository() {
        return get().typeRepo;
    }

    @Fold
    public static JfrSymbolRepository getSymbolRepository() {
        return get().symbolRepo;
    }

    @Fold
    public static JfrThreadRepository getThreadRepo() {
        return get().threadRepo;
    }

    @Fold
    public static JfrMethodRepository getMethodRepo() {
        return get().methodRepo;
    }

    @Fold
    public static JfrStackTraceRepository getStackTraceRepo() {
        return get().stackTraceRepo;
    }

    @Fold
    public static JfrLogging getLogging() {
        return get().jfrLogging;
    }

    @Fold
    public static JfrOldObjectProfiler getOldObjectProfiler() {
        return get().oldObjectProfiler;
    }

    @Fold
    public static JfrOldObjectRepository getOldObjectRepository() {
        return get().oldObjectRepo;
    }

    @Fold
    public static JfrEventThrottling getEventThrottling() {
        return get().eventThrottler;
    }

    @Uninterruptible(reason = "Prevent races with VM operations that start/stop recording.", callerMustBe = true)
    protected boolean isRecording() {
        return recording;
    }

    /**
     * See {@link JVM#createJFR}. Until {@link #beginRecording} is executed, no JFR events can be
     * triggered yet. So, we don't need to take any precautions here.
     */
    public boolean createJFR(boolean simulateFailure) {
        if (simulateFailure) {
            throw new IllegalStateException("Unable to start JFR");
        } else if (initialized) {
            throw new IllegalStateException("JFR was already started before");
        }

        options.validateAndAdjustMemoryOptions();

        JfrTicks.initialize();

        long threadLocalBufferSize = options.threadBufferSize.getValue();
        assert threadLocalBufferSize > 0;
        threadLocal.initialize(Word.unsigned(threadLocalBufferSize));

        long globalBufferSize = options.globalBufferSize.getValue();
        assert globalBufferSize > 0;
        globalMemory.initialize(Word.unsigned(globalBufferSize), options.globalBufferCount.getValue());

        unlockedChunkWriter.initialize(options.maxChunkSize.getValue());
        stackTraceRepo.setStackTraceDepth(NumUtil.safeToInt(options.stackDepth.getValue()));

        recorderThread.start();

        initialized = true;
        return true;
    }

    /**
     * See {@link JVM#destroyJFR}. This method is only called after the recording was already
     * stopped. As no JFR events can be triggered by the current or any other thread, we don't need
     * to take any precautions here.
     */
    public boolean destroyJFR() {
        assert !recording : "must already have been stopped";
        if (!initialized) {
            return false;
        }

        recorderThread.shutdown();

        JfrTeardownOperation vmOp = new JfrTeardownOperation();
        vmOp.enqueue();

        return true;
    }

    @Uninterruptible(reason = "Result is only valid until epoch changes.", callerMustBe = true)
    public long getStackTraceId(long eventTypeId, int skipCount) {
        if (isStackTraceEnabled(eventTypeId)) {
            return getStackTraceId(skipCount);
        } else {
            return 0L;
        }
    }

    /**
     * See {@link JVM#getStackTraceId}.
     */
    @Uninterruptible(reason = "Result is only valid until epoch changes.", callerMustBe = true)
    public long getStackTraceId(int skipCount) {
        if (isRecording()) {
            return stackTraceRepo.getStackTraceId(skipCount);
        }
        return 0L;
    }

    @Uninterruptible(reason = "Result is only valid until epoch changes.", callerMustBe = true)
    public long getStackTraceId(JfrEvent eventType) {
        return getStackTraceId(eventType.getId(), eventType.getSkipCount());
    }

    /**
     * See {@link JVM#getThreadId}.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long getThreadId(Thread thread) {
        if (HasJfrSupport.get()) {
            return JavaThreads.getThreadId(thread);
        }
        return 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long getCurrentThreadId() {
        if (HasJfrSupport.get()) {
            return JavaThreads.getCurrentThreadId();
        }
        return 0;
    }

    /**
     * See {@link JVM#storeMetadataDescriptor}.
     */
    public void storeMetadataDescriptor(byte[] bytes) {
        JfrChunkWriter chunkWriter = unlockedChunkWriter.lock();
        try {
            chunkWriter.setMetadata(bytes);
        } finally {
            chunkWriter.unlock();
        }
    }

    /**
     * See {@link JVM#beginRecording}.
     */
    public void beginRecording() {
        if (recording) {
            return;
        }

        JfrChunkWriter chunkWriter = unlockedChunkWriter.lock();
        try {
            // It is possible that setOutput was called with a filename earlier. In that case, we
            // need to start recording to the specified file right away.
            chunkWriter.maybeOpenFile();
        } finally {
            chunkWriter.unlock();
        }

        JfrBeginRecordingOperation vmOp = new JfrBeginRecordingOperation();
        vmOp.enqueue();
    }

    /**
     * See {@link JVM#endRecording}.
     */
    public void endRecording() {
        if (!recording) {
            return;
        }

        recorderThread.endRecording();
    }

    /**
     * See {@link JVM#getClassId}.
     */
    @Uninterruptible(reason = "Result is only valid until epoch changes.", callerMustBe = true)
    public long getClassId(Class<?> clazz) {
        if (isRecording()) {
            return typeRepo.getClassId(clazz);
        }
        return 0L;
    }

    /**
     * See {@link JVM#setOutput}. The JFR infrastructure also calls this method when it is time to
     * rotate the file.
     */
    public void setOutput(String file) {
        JfrChunkWriter chunkWriter = unlockedChunkWriter.lock();
        try {
            if (recording) {
                boolean existingFile = chunkWriter.hasOpenFile();
                if (existingFile) {
                    chunkWriter.closeFile();
                }
                if (file != null) {
                    chunkWriter.openFile(file);
                    // If in-memory recording was active so far, we should notify the recorder
                    // thread because the global memory buffers could be rather full.
                    if (!existingFile) {
                        recorderThread.signal();
                    }
                }
            } else {
                chunkWriter.setFilename(file);
            }
        } finally {
            chunkWriter.unlock();
        }
    }

    /**
     * See {@link JVM#setFileNotification}.
     */
    public void setFileNotification(long delta) {
        options.maxChunkSize.setUserValue(delta);
    }

    /**
     * See {@link JVM#setGlobalBufferCount}.
     */
    public void setGlobalBufferCount(long count) {
        options.globalBufferCount.setUserValue(count);
    }

    /**
     * See {@link JVM#setGlobalBufferSize}.
     */
    public void setGlobalBufferSize(long size) {
        options.globalBufferSize.setUserValue(size);
    }

    /**
     * See {@link JVM#setMemorySize}.
     */
    public void setMemorySize(long size) {
        options.memorySize.setUserValue(size);
    }

    /**
     * See {@code JVM#setMethodSamplingInterval}.
     */
    public void setMethodSamplingInterval(long type, long intervalMillis) {
        if (type != JfrEvent.ExecutionSample.getId()) {
            // JFR is currently only supporting ExecutionSample event, but this method is called
            // during JFR startup, so we can't throw an error.
            return;
        }

        JfrExecutionSampler.singleton().setIntervalMillis(intervalMillis);

        if (intervalMillis > 0) {
            setStackTraceEnabled(type, true);
            setEnabled(type, true);
        }

        updateSampler();
    }

    @Uninterruptible(reason = "Prevent races with VM operations that start/stop recording.")
    private void updateSampler() {
        if (recording) {
            updateSampler0();
        }
    }

    @Uninterruptible(reason = "The executed VM operation rechecks if JFR recording is active.", calleeMustBe = false)
    private static void updateSampler0() {
        JfrExecutionSampler.singleton().update();
    }

    /**
     * See {@code JVM#setSampleThreads}.
     */
    public void setSampleThreads(boolean sampleThreads) {
        setEnabled(JfrEvent.ExecutionSample.getId(), sampleThreads);
        setEnabled(JfrEvent.NativeMethodSample.getId(), sampleThreads);
    }

    /**
     * See {@link JVM#setCompressedIntegers}.
     */
    public void setCompressedIntegers(boolean compressed) {
        if (!compressed) {
            throw new IllegalStateException("JFR currently only supports compressed integers.");
        }
    }

    /**
     * See {@link JVM#setStackDepth}.
     */
    public void setStackDepth(int depth) {
        options.stackDepth.setUserValue(depth);
    }

    /**
     * See {@link JVM#setStackTraceEnabled}.
     */
    public void setStackTraceEnabled(long eventTypeId, boolean enabled) {
        eventSettings[NumUtil.safeToInt(eventTypeId)].setStackTrace(enabled);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isStackTraceEnabled(long eventTypeId) {
        assert (int) eventTypeId == eventTypeId;
        return eventSettings[(int) eventTypeId].hasStackTrace();
    }

    /**
     * See {@link JVM#setThreadBufferSize}.
     */
    public void setThreadBufferSize(long size) {
        options.threadBufferSize.setUserValue(size);
    }

    /**
     * See {@link JVM#flush}.
     */
    @Uninterruptible(reason = "Accesses a JFR buffer.")
    public boolean flush(Target_jdk_jfr_internal_event_EventWriter writer, int uncommittedSize, int requestedSize) {
        assert writer != null;
        assert uncommittedSize >= 0;

        JfrBuffer oldBuffer = threadLocal.getJavaBuffer();
        assert oldBuffer.isNonNull() : "Java EventWriter should not be used otherwise";
        JfrBuffer newBuffer = JfrThreadLocal.flushToGlobalMemory(oldBuffer, Word.unsigned(uncommittedSize), requestedSize);
        if (newBuffer.isNull()) {
            /* The flush failed, so mark the EventWriter as invalid for this write attempt. */
            JfrEventWriterAccess.update(writer, oldBuffer, 0, false);
        } else {
            JfrEventWriterAccess.update(writer, newBuffer, uncommittedSize, true);
        }

        /*
         * Return false to signal that there is no need to do another flush at the end of the
         * current event.
         */
        return false;
    }

    public void flush() {
        JfrChunkWriter chunkWriter = unlockedChunkWriter.lock();
        try {
            if (recording) {
                boolean existingFile = chunkWriter.hasOpenFile();
                if (existingFile) {
                    chunkWriter.flush();
                }
            }
        } finally {
            chunkWriter.unlock();
        }
    }

    @Uninterruptible(reason = "Accesses a native JFR buffer.")
    public long commit(long nextPosition) {
        assert nextPosition != 0 : "invariant";

        JfrBuffer current = threadLocal.getExistingJavaBuffer();
        if (current.isNull()) {
            /* This is a commit for a recording session that is no longer active - ignore it. */
            return nextPosition;
        }

        Pointer next = Word.pointer(nextPosition);
        assert next.aboveOrEqual(current.getCommittedPos()) : "invariant";
        assert next.belowOrEqual(JfrBufferAccess.getDataEnd(current)) : "invariant";
        if (JfrThreadLocal.isNotified()) {
            JfrThreadLocal.clearNotification();
            return current.getCommittedPos().rawValue();
        }
        current.setCommittedPos(next);
        return nextPosition;
    }

    public void markChunkFinal() {
        JfrChunkWriter chunkWriter = unlockedChunkWriter.lock();
        try {
            if (recording) {
                boolean existingFile = chunkWriter.hasOpenFile();
                if (existingFile) {
                    chunkWriter.markChunkFinal();
                }
            }
        } finally {
            chunkWriter.unlock();
        }
    }

    /**
     * See {@link JVM#setRepositoryLocation}.
     */
    public void setRepositoryLocation(@SuppressWarnings("unused") String dirText) {
        // Would only be used in case of an emergency dump, which is not supported at the moment.
    }

    /**
     * See {@code JfrEmergencyDump::set_dump_path}.
     */
    public void setDumpPath(String dumpPathText) {
        dumpPath = dumpPathText;
    }

    /**
     * See {@code JVM#getDumpPath()}.
     */
    public String getDumpPath() {
        if (dumpPath == null) {
            dumpPath = Target_jdk_jfr_internal_util_Utils.getPathInProperty("user.home", null).toString();
        }
        return dumpPath;
    }

    /**
     * See {@link JVM#abort}.
     */
    public void abort(String errorMsg) {
        throw VMError.shouldNotReachHere(errorMsg);
    }

    /**
     * See {@link JVM#shouldRotateDisk}.
     */
    public boolean shouldRotateDisk() {
        JfrChunkWriter chunkWriter = unlockedChunkWriter.lock();
        try {
            return chunkWriter.shouldRotateDisk();
        } finally {
            chunkWriter.unlock();
        }
    }

    /**
     * See {@link JVM#emitOldObjectSamples(long, boolean, boolean)}.
     */
    void emitOldObjectSamples(long cutoff, boolean emitAll, boolean skipBFS) {
        oldObjectProfiler.emit(cutoff, emitAll, skipBFS);
    }

    public long getChunkStartNanos() {
        JfrChunkWriter chunkWriter = unlockedChunkWriter.lock();
        try {
            return chunkWriter.getChunkStartNanos();
        } finally {
            chunkWriter.unlock();
        }
    }

    /**
     * See {@link JVM#log}.
     */
    public void log(int tagSetId, int level, String message) {
        jfrLogging.log(tagSetId, level, message);
    }

    /**
     * See {@link JVM#logEvent}.
     */
    public void logEvent(int level, String[] lines, boolean system) {
        jfrLogging.logEvent(level, lines, system);
    }

    /**
     * See {@link JVM#subscribeLogLevel}.
     */
    public void subscribeLogLevel(@SuppressWarnings("unused") LogTag lt, @SuppressWarnings("unused") int tagSetId) {
        // Currently unused because logging support is minimal.
    }

    /**
     * See {@link JVM#getEventWriter}.
     */
    public Target_jdk_jfr_internal_event_EventWriter getEventWriter() {
        return JfrThreadLocal.getEventWriter();
    }

    /**
     * See {@link JVM#newEventWriter}.
     */
    public Target_jdk_jfr_internal_event_EventWriter newEventWriter() {
        return threadLocal.newEventWriter();
    }

    /**
     * See {@link JVM#setEnabled}.
     */
    public void setEnabled(long eventTypeId, boolean newValue) {
        boolean oldValue = eventSettings[NumUtil.safeToInt(eventTypeId)].isEnabled();
        if (newValue != oldValue) {
            eventSettings[NumUtil.safeToInt(eventTypeId)].setEnabled(newValue);

            if (eventTypeId == JfrEvent.ExecutionSample.getId()) {
                updateSampler();
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isEnabled(JfrEvent event) {
        return eventSettings[(int) event.getId()].isEnabled();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void setLarge(JfrEvent event, boolean large) {
        eventSettings[(int) event.getId()].setLarge(large);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isLarge(JfrEvent event) {
        return eventSettings[(int) event.getId()].isLarge();
    }

    /**
     * See {@link JVM#setThrottle}.
     */
    public boolean setThrottle(long eventTypeId, long eventSampleSize, long periodMs) {
        return eventThrottler.setThrottle(eventTypeId, eventSampleSize, periodMs);
    }

    /**
     * See {@link JVM#setThreshold}.
     */
    public boolean setThreshold(long eventTypeId, long ticks) {
        eventSettings[NumUtil.safeToInt(eventTypeId)].setThresholdTicks(ticks);
        return true;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    long getThresholdTicks(JfrEvent event) {
        return eventSettings[(int) event.getId()].getThresholdTicks();
    }

    /**
     * See {@link JVM#setCutoff}.
     */
    public boolean setCutoff(long eventTypeId, long cutoffTicks) {
        eventSettings[NumUtil.safeToInt(eventTypeId)].setCutoffTicks(cutoffTicks);
        return true;
    }

    public boolean setConfiguration(Class<? extends Event> eventClass, Object configuration) {
        DynamicHub.fromClass(eventClass).setJrfEventConfiguration(configuration);
        return true;
    }

    public Object getConfiguration(Class<? extends Event> eventClass) {
        return DynamicHub.fromClass(eventClass).getJfrEventConfiguration();
    }

    private static class JfrBeginRecordingOperation extends JavaVMOperation {
        JfrBeginRecordingOperation() {
            super(VMOperationInfos.get(JfrBeginRecordingOperation.class, "JFR begin recording", SystemEffect.SAFEPOINT));
        }

        @Override
        protected void operate() {
            SubstrateJVM.getOldObjectProfiler().reset();
            JfrAllocationEvents.reset();

            SubstrateJVM.get().recording = true;
            /* Recording is enabled, so JFR events can be triggered at any time. */
            SubstrateJVM.getThreadRepo().registerRunningThreads();

            JfrExecutionSampler.singleton().update();
        }
    }

    static class JfrEndRecordingOperation extends JavaVMOperation {
        JfrEndRecordingOperation() {
            super(VMOperationInfos.get(JfrEndRecordingOperation.class, "JFR end recording", SystemEffect.SAFEPOINT));
        }

        /**
         * When the safepoint ends, it is guaranteed that all {@link JfrNativeEventWriter}s finished
         * their job and that no further JFR events will be triggered. It is also guaranteed that no
         * thread executes any code related to the execution sampling.
         */
        @Override
        protected void operate() {
            if (!SubstrateJVM.get().recording) {
                return;
            }

            SubstrateJVM.get().recording = false;
            JfrExecutionSampler.singleton().update();

            if (SubstrateSigprofHandler.Options.JfrBasedExecutionSamplerStatistics.getValue()) {
                printSamplerStatistics();
            }

            /* No further JFR events are emitted, so free some JFR-related buffers. */
            for (IsolateThread isolateThread = VMThreads.firstThread(); isolateThread.isNonNull(); isolateThread = VMThreads.nextThread(isolateThread)) {
                JfrThreadLocal.stopRecording(isolateThread, false);
            }

            /* Process any remaining full buffers (if there are any). */
            SamplerBuffersAccess.processFullBuffers(false);

            /*
             * If JFR recording is restarted later on, then it needs to start with a clean state.
             * Therefore, we clear all data that is still pending.
             */
            SubstrateJVM.getThreadLocal().teardown();
            SubstrateJVM.getSamplerBufferPool().teardown();
            SubstrateJVM.getGlobalMemory().clear();
            SubstrateJVM.getOldObjectProfiler().teardown();
        }
    }

    private static void printSamplerStatistics() {
        long missedSamples = SamplerStatistics.singleton().getMissedSamples();
        long unparseableStacks = SamplerStatistics.singleton().getUnparseableSamples();
        for (IsolateThread isolateThread = VMThreads.firstThread(); isolateThread.isNonNull(); isolateThread = VMThreads.nextThread(isolateThread)) {
            missedSamples += JfrThreadLocal.getMissedSamples(isolateThread);
            unparseableStacks += JfrThreadLocal.getUnparseableStacks(isolateThread);
        }

        Log log = Log.log();
        log.string("JFR sampler statistics").indent(true);
        log.string("Missed samples: ").unsigned(missedSamples).newline();
        log.string("Unparseable stacks: ").unsigned(unparseableStacks).indent(false);
    }

    private class JfrTeardownOperation extends JavaVMOperation {
        JfrTeardownOperation() {
            super(VMOperationInfos.get(JfrTeardownOperation.class, "JFR teardown", SystemEffect.SAFEPOINT));
        }

        @Override
        protected void operate() {
            if (!initialized) {
                return;
            }

            globalMemory.teardown();
            symbolRepo.teardown();
            threadRepo.teardown();
            stackTraceRepo.teardown();
            methodRepo.teardown();
            typeRepo.teardown();
            oldObjectRepo.teardown();

            initialized = false;
        }
    }
}
