package com.limelight.console;

import android.app.Activity;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.NvApp;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Objects;

/** Assembles a real controller-owned Moonlight session for ConsoleActivity. */
final class MoonlightConsoleSessionFactory implements
        MoonlightConsoleResolvedStreamRuntime.SessionFactory {
    interface Environment {
        StreamRendererConfiguration rendererConfiguration(
                PreferenceConfiguration preferences,
                StreamLaunchParameters parameters);
        float prepareDisplayForRendering(PreferenceConfiguration preferences);
        CrashListener crashListener();
        PerfOverlayListener performanceListener();
        Runnable firstFrameRenderedCallback();
        ConsoleSessionInput createInput(
                StreamInputSender inputSender,
                MediaCodecDecoderRenderer renderer,
                PreferenceConfiguration preferences);
        void onInputReady(ConsoleSessionInput input);
        void onConfigurationPlanned(StreamSessionConfigurationPlanner.Plan plan);
        Runnable quitHostApplication();
    }

    private final Activity activity;
    private final Environment environment;

    MoonlightConsoleSessionFactory(Activity activity, Environment environment) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override public MoonlightConsoleResolvedStreamRuntime.Session create(
            StreamLaunchParameters parameters,
            ConsoleResolvedStreamRuntime.Listener listener) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(listener, "listener");

        PreferenceConfiguration preferences = StreamPreferenceContext.load(
                activity, parameters);
        StreamRendererConfiguration rendererConfiguration =
                Objects.requireNonNull(environment.rendererConfiguration(
                        preferences, parameters), "rendererConfiguration");
        MediaCodecHelper.initialize(activity, rendererConfiguration.glRenderer);

        MoonlightStreamSessionController controller =
                new MoonlightStreamSessionController(
                        command -> new Thread(command,
                                "MoonWaker unified transport stop").start(),
                        activity::runOnUiThread,
                        Objects.requireNonNull(environment.quitHostApplication(),
                                "quitHostApplication"));
        boolean rendererPrepared = false;
        DeferredConsoleSessionInput input = null;
        try {
            MediaCodecDecoderRenderer renderer = controller.prepareRenderer(
                    activity,
                    rendererConfiguration,
                    environment.crashListener(),
                    environment.performanceListener(),
                    environment.firstFrameRenderedCallback());
            rendererPrepared = true;
            renderer.setSeamlessFrameRateOnly(true);

            float displayRefreshRate = environment.prepareDisplayForRendering(preferences);
            StreamSessionConfigurationPlanner.DecoderCapabilities decoder =
                    new StreamSessionConfigurationPlanner.DecoderCapabilities(
                            renderer.isHevcSupported(),
                            renderer.isHevcMain10Hdr10Supported(),
                            renderer.isAv1Supported(),
                            renderer.isAv1Main10Supported(),
                            renderer.getPreferredColorSpace(),
                            renderer.getPreferredColorRange());
            NvApp app = new NvApp(parameters.appName, parameters.appId,
                    parameters.appSupportsHdr);
            StreamSessionConfigurationPlanner.Plan plan =
                    StreamSessionConfigurationPlanner.plan(
                            preferences,
                            app,
                            rendererConfiguration.requestedHdr,
                            displayRefreshRate,
                            ControllerHandler.getAttachedControllerMask(activity),
                            decoder);
            preferences.framePacing = plan.effectiveFramePacing;
            environment.onConfigurationPlanned(plan);

            input = new DeferredConsoleSessionInput();
            ConsoleSessionEventCoordinator coordinator =
                    new ConsoleSessionEventCoordinator(
                            new ConsoleSessionEventCoordinator.Lifecycle() {
                                @Override public void onStarted() {
                                    controller.onConnectionStarted();
                                }

                                @Override public void onFailed() {
                                    controller.onConnectionFailed();
                                }

                                @Override public void onTerminated() {
                                    controller.onConnectionTerminated();
                                }
                            },
                            listener,
                            input);
            ConsoleNvConnectionListener connectionListener =
                    new ConsoleNvConnectionListener(coordinator);
            StreamConfiguration streamConfiguration = plan.configuration;
            StreamTransportConfiguration transportConfiguration =
                    StreamTransportConfiguration.from(
                            parameters,
                            streamConfiguration,
                            PlatformBinding.getCryptoProvider(activity),
                            preferences.enableAudioFx);
            controller.initializeTransport(
                    activity.getApplicationContext(),
                    activity,
                    transportConfiguration,
                    connectionListener);
            ConsoleSessionInput boundInput = Objects.requireNonNull(
                    environment.createInput(
                            controller.inputSender(), renderer, preferences),
                    "sessionInput");
            input.bind(boundInput);
            environment.onInputReady(input);
            return MoonlightConsoleSession.create(controller, input::close);
        } catch (RuntimeException error) {
            if (input != null) {
                input.close();
            }
            if (rendererPrepared) {
                controller.prepareRendererForStop();
                controller.disconnectTransport();
            }
            throw error;
        }
    }
}
