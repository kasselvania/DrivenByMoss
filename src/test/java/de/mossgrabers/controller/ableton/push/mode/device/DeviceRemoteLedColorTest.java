// Pushwig remote-slot LED proof (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.mode.device;

import de.mossgrabers.controller.ableton.push.PushConfiguration;
import de.mossgrabers.controller.ableton.push.PushVersion;
import de.mossgrabers.controller.ableton.push.controller.PushColorManager;
import de.mossgrabers.controller.ableton.push.controller.PushControlSurface;
import de.mossgrabers.controller.ableton.push.mode.track.MasterMode;
import de.mossgrabers.controller.ableton.push.mode.track.TrackMode;
import de.mossgrabers.controller.ableton.push.mode.track.VolumeMode;
import de.mossgrabers.framework.controller.ButtonID;
import de.mossgrabers.framework.controller.ContinuousID;
import de.mossgrabers.framework.controller.hardware.IHwButton;
import de.mossgrabers.framework.controller.hardware.IHwRelativeKnob;
import de.mossgrabers.framework.controller.hardware.IHwSurfaceFactory;
import de.mossgrabers.framework.controller.valuechanger.IValueChanger;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.IModel;
import de.mossgrabers.framework.daw.IProject;
import de.mossgrabers.framework.daw.data.ICursorDevice;
import de.mossgrabers.framework.daw.data.ITrack;
import de.mossgrabers.framework.daw.data.IMasterTrack;
import de.mossgrabers.framework.daw.data.bank.IDeviceBank;
import de.mossgrabers.framework.daw.data.bank.IParameterBank;
import de.mossgrabers.framework.daw.data.bank.IParameterPageBank;
import de.mossgrabers.framework.daw.data.bank.ITrackBank;
import de.mossgrabers.framework.daw.midi.IMidiInput;
import de.mossgrabers.framework.daw.midi.IMidiOutput;
import de.mossgrabers.framework.featuregroup.ModeManager;
import de.mossgrabers.framework.mode.Modes;
import de.mossgrabers.framework.observer.IBankPageObserver;
import de.mossgrabers.framework.parameter.IParameter;
import de.mossgrabers.framework.utils.ButtonEvent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;


/** Runs the production modes, mode manager and bank provider against fake DAW/hardware endpoints. */
public final class DeviceRemoteLedColorTest
{
    private static final int [] EXPECTED = { 5, 9, 13, 17, 25, 37, 48, 56 };
    private static int checks;

    public static void main (final String [] arguments)
    {
        final Fixture f = new Fixture (PushVersion.VERSION_3);
        f.manager.setActive (Modes.VOLUME);
        final int [] volumeColors = f.colors ();
        f.manager.setActive (Modes.TRACK);
        final int [] trackColors = f.colors ();
        f.manager.setActive (Modes.DEVICE_PARAMS);
        testLivePresentationData (f);
        check (Arrays.equals (f.colors (), EXPECTED), "Upper physical row must identify all eight remote slots");
        for (int i = 0; i < 8; i++)
        {
            check (f.bound[i] == f.remotes[i], "Existing binding must target the corresponding remote");
            check (f.colorManager.getColor (EXPECTED[i], ButtonID.get (ButtonID.ROW2_1, i)) != null, "LED palette entry must exist");
            check (f.device.getButtonColor (ButtonID.get (ButtonID.ROW1_1, i)) == (i == 0 ? 9 : 15), "Lower device-selection row must remain unchanged");
        }

        final int bindings = f.bindings;
        for (int iteration = 0; iteration < 100; iteration++)
            check (Arrays.equals (f.colors (), EXPECTED), "Repeated LED reads must be stable");
        check (f.bindings == bindings && f.midiWrites == 0, "Color reads must not bind controls or send MIDI themselves");

        f.exists[2] = false;
        check (f.colors ()[2] == 0, "Unassigned remote must be dark");
        f.exists[2] = true;
        f.deviceExists = false;
        check (Arrays.equals (f.colors (), new int [8]), "Missing device must not retain slot colors");
        f.deviceExists = true;
        check (Arrays.equals (f.colors (), EXPECTED), "Current device returning must restore its slot colors");

        // Exercise the real BankParameterProvider's page observer, not a parallel rebind model.
        f.remotes[0] = f.parameter (0, "Pitch after reassignment");
        for (final IBankPageObserver observer: List.copyOf (f.pageObservers))
            observer.pageAdjusted ();
        check (f.bound[0] == f.remotes[0], "Page adjustment must rebind to the new remote object");
        check (Arrays.equals (f.colors (), EXPECTED), "Remote replacement must retain encoder-slot identity");

        f.device.onSecondRow (0, ButtonEvent.DOWN);
        f.device.onSecondRow (1, ButtonEvent.DOWN);
        f.device.onSecondRow (2, ButtonEvent.DOWN);
        f.device.onSecondRow (5, ButtonEvent.DOWN);
        f.device.onSecondRow (6, ButtonEvent.DOWN);
        check (f.actions.equals (List.of ("toggleEnabledState", "toggleParameterPageSectionVisible", "toggleExpanded", "togglePinned", "toggleWindowOpen")), "Upper-row button actions must be unchanged");
        f.device.onSecondRow (4, ButtonEvent.DOWN);
        check (!f.device.isShowDevices (), "Banks button must still change the lower-row page selector");
        for (int i = 0; i < 8; i++)
            check (f.device.getButtonColor (ButtonID.get (ButtonID.ROW1_1, i)) == (i == 2 ? 9 : 15), "Lower page-selection colors must remain unchanged");
        check (Arrays.equals (f.colors (), EXPECTED), "Showing devices versus pages must not change encoder colors");
        f.device.onSecondRow (3, ButtonEvent.DOWN);
        check (f.manager.getActiveID () == Modes.DEVICE_CHAINS, "Chains action must still select the existing mode");
        check (!Arrays.equals (f.colors (), EXPECTED), "Chains must not inherit the remote-slot palette");

        f.manager.setActive (Modes.DEVICE_PARAMS);
        for (int i = 0; i < 3; i++)
        {
            f.manager.setActive (Modes.VOLUME);
            check (Arrays.equals (f.colors (), volumeColors), "Volume must recover its original LED feedback");
            f.manager.setActive (Modes.TRACK);
            check (Arrays.equals (f.colors (), trackColors), "Track must recover its original LED feedback");
            f.manager.setActive (Modes.DEVICE_PARAMS);
            check (Arrays.equals (f.colors (), EXPECTED), "Returning to Device Parameters must restore the mapping colors");
            f.manager.setTemporary (Modes.MASTER_TEMP);
            check (f.colors ()[0] == 0 && f.colors ()[6] == 21, "Temporary Master must own its normal LED feedback");
            check (f.device.getButtonColor (ButtonID.ROW2_1) != EXPECTED[0], "Even an inactive Device mode query must not claim temporary Master context");
            f.manager.restore ();
            check (Arrays.equals (f.colors (), EXPECTED), "Master return must restore the mapping colors");
            for (int slot = 0; slot < 8; slot++)
                check (f.bound[slot] == f.remotes[slot], "Mode return must restore current remote bindings");
        }

        for (final PushVersion version: List.of (PushVersion.VERSION_1, PushVersion.VERSION_2))
        {
            final Fixture legacy = new Fixture (version);
            final int [] beforeActivation = legacy.deviceColors ();
            legacy.manager.setActive (Modes.DEVICE_PARAMS);
            check (Arrays.equals (legacy.colors (), beforeActivation), "Push 1/2 function colors must remain unchanged");
            check (!Arrays.equals (legacy.colors (), EXPECTED), "No new mapping palette on unqualified hardware");
        }
        System.out.println ("DeviceRemoteLedColorTest: PASS (" + checks + " checks)");
    }

    private static void check (final boolean condition, final String message)
    {
        checks++;
        if (!condition)
            throw new AssertionError (message);
    }


    private static void testLivePresentationData (final Fixture f)
    {
        final int before = f.bindings;
        var presentation = f.device.createSamplerLensPresentation ();
        check (presentation != null, "Device Parameters can supply current compact readouts");
        for (int i = 0; i < 8; i++)
        {
            check (presentation.getSlots ().get (i).alias ().equals ("Remote " + i), "Alias comes from current parameter bank");
            check (presentation.getSlots ().get (i).value ().equals ("0.00 unit"), "Formatted value comes from the parameter, not screenshot/OCR");
        }
        f.values[0] = "113.25 %";
        f.touched[0] = true;
        f.touched[7] = true;
        presentation = f.device.createSamplerLensPresentation ();
        check (presentation.getSlots ().get (0).value ().equals ("113.25 %"), "Value changes without screen recognition or touch gating");
        check (presentation.getSlots ().get (0).touched () && presentation.getSlots ().get (7).touched (), "Concurrent raw touches are retained");
        check (presentation.getSlots ().get (0).action ().equals ("On") && presentation.getSlots ().get (0).actionActive (), "Independent button action/state survives");
        f.manager.setTemporary (Modes.MASTER_TEMP);
        check (f.device.createSamplerLensPresentation () == null, "Temporary Master cannot use Device presentation");
        f.touched[0] = false;
        f.touched[7] = false;
        f.manager.restore ();
        check (!f.device.createSamplerLensPresentation ().getSlots ().get (0).touched (), "Release outside Device mode does not retain a touch highlight");
        f.exists[2] = false;
        presentation = f.device.createSamplerLensPresentation ();
        check (!presentation.getSlots ().get (2).exists () && presentation.getSlots ().get (2).alias ().isEmpty () && presentation.getSlots ().get (2).value ().isEmpty (), "Unassigned slot does not retain old text");
        f.exists[2] = true;
        f.device.setShowDevices (false);
        presentation = f.device.createSamplerLensPresentation ();
        check (presentation.getSlots ().get (2).navigation ().equals ("Page 2") && presentation.getSlots ().get (2).navigationActive (), "Actual page navigation state is retained");
        check (presentation.getSlots ().get (4).actionActive (), "Banks action state follows existing menu owner");
        f.device.setShowDevices (true);
        final int afterModeRestore = f.bindings;
        for (int i = 0; i < 100; i++) f.device.createSamplerLensPresentation ();
        check (f.bindings == afterModeRestore && afterModeRestore > before, "Only real mode switching rebinds; presentation reads do not");
        f.deviceExists = false;
        check (f.device.createSamplerLensPresentation () == null, "Missing device refuses compact presentation");
        f.deviceExists = true;
    }

    private static final class Fixture
    {
        private final boolean [] exists = { true, true, true, true, true, true, true, true };
        private final boolean [] touched = new boolean [8];
        private final String [] values = { "0.00 unit", "0.00 unit", "0.00 unit", "0.00 unit", "0.00 unit", "0.00 unit", "0.00 unit", "0.00 unit" };
        private boolean deviceExists = true;
        private final IParameter [] remotes = new IParameter [8];
        private final IParameter [] bound = new IParameter [8];
        private final List<IBankPageObserver> pageObservers = new ArrayList<> ();
        private final List<String> actions = new ArrayList<> ();
        private int bindings;
        private int midiWrites;
        private final PushColorManager colorManager;
        private final DeviceParamsMode device;
        private final ModeManager manager;

        private Fixture (final PushVersion version)
        {
            for (int i = 0; i < 8; i++)
                this.remotes[i] = this.parameter (i, "Remote " + i);
            this.colorManager = new PushColorManager (version);
            final IHwSurfaceFactory factory = proxy (IHwSurfaceFactory.class, (p, m, a) -> {
                if (m.getName ().equals ("createButton"))
                    return proxy (IHwButton.class, null);
                if (m.getName ().equals ("createRelativeKnob"))
                {
                    final int index = ((ContinuousID) a[1]).ordinal () - ContinuousID.KNOB1.ordinal ();
                    return proxy (IHwRelativeKnob.class, (knob, method, args) -> {
                        if (method.getName ().equals ("isTouched"))
                            return this.touched[index];
                        if (method.getName ().equals ("bind") && args[0] instanceof IParameter parameter)
                        {
                            this.bound[index] = parameter;
                            this.bindings++;
                        }
                        if (method.getName ().equals ("unbind"))
                            this.bound[index] = null;
                        return fallback (method.getReturnType ());
                    });
                }
                return fallback (m.getReturnType ());
            });
            final IHost host = proxy (IHost.class, (p, m, a) -> switch (m.getName ()) {
                case "createSurfaceFactory" -> factory;
                case "supports" -> Boolean.TRUE;
                default -> fallback (m.getReturnType ());
            });
            final IValueChanger valueChanger = proxy (IValueChanger.class, null);
            final PushConfiguration configuration = new PushConfiguration (host, valueChanger, List.of (), version);
            final IMidiOutput output = proxy (IMidiOutput.class, (p, m, a) -> {
                this.midiWrites++;
                return fallback (m.getReturnType ());
            });
            final PushControlSurface surface = new PushControlSurface (host, this.colorManager, configuration, output, proxy (IMidiInput.class, null));
            for (int i = 0; i < 8; i++)
                surface.createRelativeKnob (ContinuousID.get (ContinuousID.KNOB1, i), "Knob " + i);

            final IParameterPageBank pages = proxy (IParameterPageBank.class, (p, m, a) -> switch (m.getName ()) {
                case "getItem" -> "Page " + a[0];
                case "getSelectedItemIndex" -> 2;
                default -> fallback (m.getReturnType ());
            });
            final IParameterBank bank = proxy (IParameterBank.class, (p, m, a) -> {
                switch (m.getName ())
                {
                    case "getPageSize": return 8;
                    case "getItem": return this.remotes[(Integer) a[0]];
                    case "getPageBank": return pages;
                    case "addPageObserver": this.pageObservers.add ((IBankPageObserver) a[0]); break;
                    case "removePageObserver": this.pageObservers.remove (a[0]); break;
                    default: break;
                }
                return fallback (m.getReturnType ());
            });
            final IDeviceBank devices = proxy (IDeviceBank.class, (p, m, a) -> m.getName ().equals ("getItem") ? proxy (ICursorDevice.class, (item, method, args) -> method.getName ().equals ("doesExist") ? true : fallback (method.getReturnType ())) : fallback (m.getReturnType ()));
            final ICursorDevice cursor = proxy (ICursorDevice.class, (p, m, a) -> {
                if (m.getName ().startsWith ("toggle"))
                    this.actions.add (m.getName ());
                return switch (m.getName ()) {
                    case "getParameterBank" -> bank;
                    case "getDeviceBank" -> devices;
                    case "doesExist" -> this.deviceExists;
                    case "getSlotChains" -> new String [] { "Chain" };
                    case "isEnabled" -> true;
                    default -> fallback (m.getReturnType ());
                };
            });
            final ITrack track = proxy (ITrack.class, (p, m, a) -> m.getName ().equals ("getVolumeParameter") ? this.remotes[0] : fallback (m.getReturnType ()));
            final ITrackBank tracks = proxy (ITrackBank.class, (p, m, a) -> switch (m.getName ()) {
                case "getPageSize" -> 8;
                case "getItem" -> track;
                case "getSelectedChannelColorEntry" -> "DAW_COLOR_ORANGE";
                default -> fallback (m.getReturnType ());
            });
            final IMasterTrack master = proxy (IMasterTrack.class, (p, m, a) -> m.getName ().endsWith ("Parameter") ? this.remotes[0] : fallback (m.getReturnType ()));
            final IProject project = proxy (IProject.class, (p, m, a) -> m.getName ().endsWith ("Parameter") ? this.remotes[0] : fallback (m.getReturnType ()));
            final IModel model = proxy (IModel.class, (p, m, a) -> switch (m.getName ()) {
                case "getCursorDevice" -> cursor;
                case "getColorManager" -> this.colorManager;
                case "getHost" -> host;
                case "getValueChanger" -> valueChanger;
                case "getCurrentTrackBank", "getTrackBank", "getEffectTrackBank" -> tracks;
                case "getCursorTrack" -> null;
                case "getMasterTrack" -> master;
                case "getProject" -> project;
                default -> fallback (m.getReturnType ());
            });
            this.manager = surface.getModeManager ();
            this.device = new DeviceParamsMode (surface, model);
            this.manager.register (Modes.DEVICE_PARAMS, this.device);
            this.manager.register (Modes.DEVICE_CHAINS, new DeviceChainsMode (surface, model));
            this.manager.register (Modes.VOLUME, new VolumeMode (surface, model));
            this.manager.register (Modes.TRACK, new TrackMode (surface, model));
            this.manager.register (Modes.MASTER_TEMP, new MasterMode (surface, model, true));
        }

        private IParameter parameter (final int index, final String name)
        {
            return proxy (IParameter.class, (p, m, a) -> switch (m.getName ()) {
                case "doesExist" -> this.exists[index];
                case "getName" -> name;
                case "getDisplayedValue" -> this.values[index];
                default -> fallback (m.getReturnType ());
            });
        }

        private int [] colors ()
        {
            final int [] result = new int [8];
            for (int i = 0; i < 8; i++)
                result[i] = this.manager.getActive ().getButtonColor (ButtonID.get (ButtonID.ROW2_1, i));
            return result;
        }

        private int [] deviceColors ()
        {
            final int [] result = new int [8];
            for (int i = 0; i < 8; i++)
                result[i] = this.device.getButtonColor (ButtonID.get (ButtonID.ROW2_1, i));
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy (final Class<T> type, final InvocationHandler handler)
    {
        return (T) Proxy.newProxyInstance (type.getClassLoader (), new Class<?> [] { type }, (p, m, a) -> {
            if (m.getName ().equals ("hashCode")) return System.identityHashCode (p);
            if (m.getName ().equals ("equals")) return p == a[0];
            if (m.getName ().equals ("toString")) return type.getSimpleName ();
            return handler == null ? fallback (m.getReturnType ()) : handler.invoke (p, m, a);
        });
    }

    private static Object fallback (final Class<?> type)
    {
        if (type == Optional.class) return Optional.empty ();
        if (type == String.class) return "";
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        return null;
    }
}
