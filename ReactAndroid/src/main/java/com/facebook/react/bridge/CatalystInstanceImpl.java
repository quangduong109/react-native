/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.bridge;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.facebook.common.logging.FLog;
import com.facebook.infer.annotation.Assertions;
import com.facebook.proguard.annotations.DoNotStrip;
import com.facebook.react.bridge.queue.ReactQueueConfiguration;
import com.facebook.react.bridge.queue.ReactQueueConfigurationImpl;
import com.facebook.react.bridge.queue.ReactQueueConfigurationSpec;
import com.facebook.react.bridge.queue.QueueThreadExceptionHandler;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.annotations.VisibleForTesting;
import com.facebook.react.common.futures.SimpleSettableFuture;
import com.facebook.systrace.Systrace;
import com.facebook.systrace.TraceListener;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/**
 * This provides an implementation of the public CatalystInstance instance.  It is public because
 * it is built by ReactInstanceManager which is in a different package.
 */
@DoNotStrip
public class CatalystInstanceImpl implements CatalystInstance {

  private static final AtomicInteger sNextInstanceIdForTrace = new AtomicInteger(1);

  // Access from any thread
  private final ReactQueueConfigurationImpl mReactQueueConfiguration;
  private final CopyOnWriteArrayList<NotThreadSafeBridgeIdleDebugListener> mBridgeIdleListeners;
  private final AtomicInteger mPendingJSCalls = new AtomicInteger(0);
  private final String mJsPendingCallsTitleForTrace =
      "pending_js_calls_instance" + sNextInstanceIdForTrace.getAndIncrement();
  private volatile boolean mDestroyed = false;
  private final TraceListener mTraceListener;
  private final JavaScriptModuleRegistry mJSModuleRegistry;
  private final JSBundleLoader mJSBundleLoader;
  private final Object mTeardownLock = new Object();

  // Access from native modules thread
  private final NativeModuleRegistry mJavaRegistry;
  private final NativeModuleCallExceptionHandler mNativeModuleCallExceptionHandler;
  private boolean mInitialized = false;

  // Access from JS thread
  private final ReactBridge mBridge;
  private boolean mJSBundleHasLoaded;

  private CatalystInstanceImpl(
      final ReactQueueConfigurationSpec ReactQueueConfigurationSpec,
      final JavaScriptExecutor jsExecutor,
      final NativeModuleRegistry registry,
      final JavaScriptModulesConfig jsModulesConfig,
      final JSBundleLoader jsBundleLoader,
      NativeModuleCallExceptionHandler nativeModuleCallExceptionHandler) {
    mReactQueueConfiguration = ReactQueueConfigurationImpl.create(
        ReactQueueConfigurationSpec,
        new NativeExceptionHandler());
    mBridgeIdleListeners = new CopyOnWriteArrayList<>();
    mJavaRegistry = registry;
    mJSModuleRegistry = new JavaScriptModuleRegistry(CatalystInstanceImpl.this, jsModulesConfig);
    mJSBundleLoader = jsBundleLoader;
    mNativeModuleCallExceptionHandler = nativeModuleCallExceptionHandler;
    mTraceListener = new JSProfilerTraceListener();

    try {
      mBridge = mReactQueueConfiguration.getJSQueueThread().callOnQueue(
          new Callable<ReactBridge>() {
            @Override
            public ReactBridge call() throws Exception {
              Systrace.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "initializeBridge");
              try {
                return initializeBridge(jsExecutor, jsModulesConfig);
              } finally {
                Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
              }
            }
          }).get();
    } catch (Exception t) {
      throw new RuntimeException("Failed to initialize bridge", t);
    }
  }

  private ReactBridge initializeBridge(
      JavaScriptExecutor jsExecutor,
      JavaScriptModulesConfig jsModulesConfig) {
    mReactQueueConfiguration.getJSQueueThread().assertIsOnThread();
    Assertions.assertCondition(mBridge == null, "initializeBridge should be called once");

    Systrace.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "ReactBridgeCtor");
    ReactBridge bridge;
    try {
      bridge = new ReactBridge(
          jsExecutor,
          new NativeModulesReactCallback(),
          mReactQueueConfiguration.getNativeModulesQueueThread());
    } finally {
      Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
    }

    Systrace.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "setBatchedBridgeConfig");
    try {
      bridge.setGlobalVariable(
          "__fbBatchedBridgeConfig",
          buildModulesConfigJSONProperty(mJavaRegistry, jsModulesConfig));
      bridge.setGlobalVariable(
          "__RCTProfileIsProfiling",
          Systrace.isTracing(Systrace.TRACE_TAG_REACT_APPS) ? "true" : "false");
    } finally {
      Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
    }

    mJavaRegistry.notifyReactBridgeInitialized(bridge);
    return bridge;
  }

  @Override
  public void runJSBundle() {
    try {
      mJSBundleHasLoaded = mReactQueueConfiguration.getJSQueueThread().callOnQueue(
          new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
              Assertions.assertCondition(!mJSBundleHasLoaded, "JS bundle was already loaded!");

              incrementPendingJSCalls();

              Systrace.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "loadJSScript");
              try {
                mJSBundleLoader.loadScript(mBridge);

                // This is registered after JS starts since it makes a JS call
                Systrace.registerListener(mTraceListener);
              } catch (JSExecutionException e) {
                mNativeModuleCallExceptionHandler.handleException(e);
              } finally {
                Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
              }

              return true;
            }
          }).get();
    } catch (Exception t) {
      throw new RuntimeException(t);
    }
  }

  /* package */ void callFunction(
      final int moduleId,
      final int methodId,
      final NativeArray arguments,
      final String tracingName) {
    synchronized (mTeardownLock) {
      if (mDestroyed) {
        FLog.w(ReactConstants.TAG, "Calling JS function after bridge has been destroyed.");
        return;
      }

      incrementPendingJSCalls();

      Assertions.assertNotNull(mBridge).callFunction(moduleId, methodId, arguments, tracingName);
    }
  }

  // This is called from java code, so it won't be stripped anyway, but proguard will rename it,
  // which this prevents.
  @DoNotStrip
  @Override
  public void invokeCallback(final int callbackID, final NativeArray arguments) {
    synchronized (mTeardownLock) {
      if (mDestroyed) {
        FLog.w(ReactConstants.TAG, "Invoking JS callback after bridge has been destroyed.");
        return;
      }

      incrementPendingJSCalls();

      Assertions.assertNotNull(mBridge).invokeCallback(callbackID, arguments);
    }
  }

  /**
   * Destroys this catalyst instance, waiting for any other threads in ReactQueueConfiguration
   * (besides the UI thread) to finish running. Must be called from the UI thread so that we can
   * fully shut down other threads.
   */
  @Override
  public void destroy() {
    UiThreadUtil.assertOnUiThread();

    synchronized (mTeardownLock) {
      if (mDestroyed) {
        return;
      }

      // TODO: tell all APIs to shut down
      mDestroyed = true;
      mJavaRegistry.notifyCatalystInstanceDestroy();

      Systrace.unregisterListener(mTraceListener);

      synchronouslyDisposeBridgeOnJSThread();
      mReactQueueConfiguration.destroy();
    }
    boolean wasIdle = (mPendingJSCalls.getAndSet(0) == 0);
    if (!wasIdle && !mBridgeIdleListeners.isEmpty()) {
      for (NotThreadSafeBridgeIdleDebugListener listener : mBridgeIdleListeners) {
        listener.onTransitionToBridgeIdle();
      }
    }

    Systrace.unregisterListener(mTraceListener);

    // We can access the Bridge from any thread now because we know either we are on the JS thread
    // or the JS thread has finished via ReactQueueConfiguration#destroy()
    mBridge.dispose();
  }

  private void synchronouslyDisposeBridgeOnJSThread() {
    final SimpleSettableFuture<Void> bridgeDisposeFuture = new SimpleSettableFuture<>();
    mReactQueueConfiguration.getJSQueueThread().runOnQueue(
      new Runnable() {
        @Override
        public void run() {
          mBridge.dispose();
          bridgeDisposeFuture.set(null);
        }
      });
    bridgeDisposeFuture.getOrThrow();
  }

  @Override
  public boolean isDestroyed() {
    return mDestroyed;
  }

  /**
   * Initialize all the native modules
   */
  @VisibleForTesting
  @Override
  public void initialize() {
    UiThreadUtil.assertOnUiThread();
    Assertions.assertCondition(
        !mInitialized,
        "This catalyst instance has already been initialized");
    mInitialized = true;
    mJavaRegistry.notifyCatalystInstanceInitialized();
  }

  @Override
  public ReactQueueConfiguration getReactQueueConfiguration() {
    return mReactQueueConfiguration;
  }

  @Override
  public <T extends JavaScriptModule> T getJSModule(Class<T> jsInterface) {
    return Assertions.assertNotNull(mJSModuleRegistry).getJavaScriptModule(jsInterface);
  }

  @Override
  public <T extends NativeModule> T getNativeModule(Class<T> nativeModuleInterface) {
    return mJavaRegistry.getModule(nativeModuleInterface);
  }

  @Override
  public Collection<NativeModule> getNativeModules() {
    return mJavaRegistry.getAllModules();
  }

  @Override
  public void handleMemoryPressure(final MemoryPressure level) {
    mReactQueueConfiguration.getJSQueueThread().runOnQueue(
        new Runnable() {
          @Override
          public void run() {
            Assertions.assertNotNull(mBridge).handleMemoryPressure(level);
          }
        });
  }

  /**
   * Adds a idle listener for this Catalyst instance. The listener will receive notifications
   * whenever the bridge transitions from idle to busy and vice-versa, where the busy state is
   * defined as there being some non-zero number of calls to JS that haven't resolved via a
   * onBatchCompleted call. The listener should be purely passive and not affect application logic.
   */
  @Override
  public void addBridgeIdleDebugListener(NotThreadSafeBridgeIdleDebugListener listener) {
    mBridgeIdleListeners.add(listener);
  }

  /**
   * Removes a NotThreadSafeBridgeIdleDebugListener previously added with
   * {@link #addBridgeIdleDebugListener}
   */
  @Override
  public void removeBridgeIdleDebugListener(NotThreadSafeBridgeIdleDebugListener listener) {
    mBridgeIdleListeners.remove(listener);
  }

  @Override
  public boolean supportsProfiling() {
    return mBridge.supportsProfiling();
  }

  @Override
  public void startProfiler(String title) {
    mBridge.startProfiler(title);
  }

  @Override
  public void stopProfiler(String title, String filename) {
    mBridge.stopProfiler(title, filename);
  }

  @VisibleForTesting
  @Override
  public void setGlobalVariable(String propName, String jsonValue) {
    mBridge.setGlobalVariable(propName, jsonValue);
  }

  private String buildModulesConfigJSONProperty(
      NativeModuleRegistry nativeModuleRegistry,
      JavaScriptModulesConfig jsModulesConfig) {
    JsonFactory jsonFactory = new JsonFactory();
    StringWriter writer = new StringWriter();
    try {
      JsonGenerator jg = jsonFactory.createGenerator(writer);
      jg.writeStartObject();
      jg.writeFieldName("remoteModuleConfig");
      nativeModuleRegistry.writeModuleDescriptions(jg);
      jg.writeFieldName("localModulesConfig");
      jsModulesConfig.writeModuleDescriptions(jg);
      jg.writeEndObject();
      jg.close();
    } catch (IOException ioe) {
      throw new RuntimeException("Unable to serialize JavaScript module declaration", ioe);
    }
    return writer.getBuffer().toString();
  }

  private void incrementPendingJSCalls() {
    int oldPendingCalls = mPendingJSCalls.getAndIncrement();
    boolean wasIdle = oldPendingCalls == 0;
    Systrace.traceCounter(
        Systrace.TRACE_TAG_REACT_JAVA_BRIDGE,
        mJsPendingCallsTitleForTrace,
        oldPendingCalls + 1);
    if (wasIdle && !mBridgeIdleListeners.isEmpty()) {
      for (NotThreadSafeBridgeIdleDebugListener listener : mBridgeIdleListeners) {
        listener.onTransitionToBridgeBusy();
      }
    }
  }

  private void decrementPendingJSCalls() {
    int newPendingCalls = mPendingJSCalls.decrementAndGet();
    // TODO(9604406): handle case of web workers injecting messages to main thread
    //Assertions.assertCondition(newPendingCalls >= 0);
    boolean isNowIdle = newPendingCalls == 0;
    Systrace.traceCounter(
        Systrace.TRACE_TAG_REACT_JAVA_BRIDGE,
        mJsPendingCallsTitleForTrace,
        newPendingCalls);

    if (isNowIdle && !mBridgeIdleListeners.isEmpty()) {
      for (NotThreadSafeBridgeIdleDebugListener listener : mBridgeIdleListeners) {
        listener.onTransitionToBridgeIdle();
      }
    }
  }

  private class NativeModulesReactCallback implements ReactCallback {

    @Override
    public void call(int moduleId, int methodId, ReadableNativeArray parameters) {
      mReactQueueConfiguration.getNativeModulesQueueThread().assertIsOnThread();

      // Suppress any callbacks if destroyed - will only lead to sadness.
      if (mDestroyed) {
        return;
      }

      mJavaRegistry.call(CatalystInstanceImpl.this, moduleId, methodId, parameters);
    }

    @Override
    public void onBatchComplete() {
      mReactQueueConfiguration.getNativeModulesQueueThread().assertIsOnThread();

      // The bridge may have been destroyed due to an exception during the batch. In that case
      // native modules could be in a bad state so we don't want to call anything on them. We
      // still want to trigger the debug listener since it allows instrumentation tests to end and
      // check their assertions without waiting for a timeout.
      if (!mDestroyed) {
        Systrace.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "onBatchComplete");
        try {
          mJavaRegistry.onBatchComplete();
        } finally {
          Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
        }
      }

      decrementPendingJSCalls();
    }
  }

  private class NativeExceptionHandler implements QueueThreadExceptionHandler {

    @Override
    public void handleException(Exception e) {
      // Any Exception caught here is because of something in JS. Even if it's a bug in the
      // framework/native code, it was triggered by JS and theoretically since we were able
      // to set up the bridge, JS could change its logic, reload, and not trigger that crash.
      mNativeModuleCallExceptionHandler.handleException(e);
      mReactQueueConfiguration.getUIQueueThread().runOnQueue(
          new Runnable() {
            @Override
            public void run() {
              destroy();
            }
          });
    }
  }

  private class JSProfilerTraceListener implements TraceListener {
    @Override
    public void onTraceStarted() {
      getJSModule(com.facebook.react.bridge.Systrace.class).setEnabled(true);
    }

    @Override
    public void onTraceStopped() {
      getJSModule(com.facebook.react.bridge.Systrace.class).setEnabled(false);
    }
  }

  public static class Builder {

    private @Nullable ReactQueueConfigurationSpec mReactQueueConfigurationSpec;
    private @Nullable JSBundleLoader mJSBundleLoader;
    private @Nullable NativeModuleRegistry mRegistry;
    private @Nullable JavaScriptModulesConfig mJSModulesConfig;
    private @Nullable JavaScriptExecutor mJSExecutor;
    private @Nullable NativeModuleCallExceptionHandler mNativeModuleCallExceptionHandler;

    public Builder setReactQueueConfigurationSpec(
        ReactQueueConfigurationSpec ReactQueueConfigurationSpec) {
      mReactQueueConfigurationSpec = ReactQueueConfigurationSpec;
      return this;
    }

    public Builder setRegistry(NativeModuleRegistry registry) {
      mRegistry = registry;
      return this;
    }

    public Builder setJSModulesConfig(JavaScriptModulesConfig jsModulesConfig) {
      mJSModulesConfig = jsModulesConfig;
      return this;
    }

    public Builder setJSBundleLoader(JSBundleLoader jsBundleLoader) {
      mJSBundleLoader = jsBundleLoader;
      return this;
    }

    public Builder setJSExecutor(JavaScriptExecutor jsExecutor) {
      mJSExecutor = jsExecutor;
      return this;
    }

    public Builder setNativeModuleCallExceptionHandler(
        NativeModuleCallExceptionHandler handler) {
      mNativeModuleCallExceptionHandler = handler;
      return this;
    }

    public CatalystInstanceImpl build() {
      return new CatalystInstanceImpl(
          Assertions.assertNotNull(mReactQueueConfigurationSpec),
          Assertions.assertNotNull(mJSExecutor),
          Assertions.assertNotNull(mRegistry),
          Assertions.assertNotNull(mJSModulesConfig),
          Assertions.assertNotNull(mJSBundleLoader),
          Assertions.assertNotNull(mNativeModuleCallExceptionHandler));
    }
  }
}
