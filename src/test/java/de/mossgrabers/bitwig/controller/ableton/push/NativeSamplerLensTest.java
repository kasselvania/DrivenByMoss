// Pushwig native-context regression proof (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt
package de.mossgrabers.bitwig.controller.ableton.push;

import com.bitwig.extension.controller.api.*;
import de.mossgrabers.controller.ableton.push.PushConfiguration;
import de.mossgrabers.controller.ableton.push.PushVersion;
import de.mossgrabers.controller.ableton.push.controller.*;
import de.mossgrabers.framework.controller.hardware.*;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.IMemoryBlock;
import de.mossgrabers.framework.daw.data.bank.IParameterBank;
import de.mossgrabers.framework.daw.data.bank.IParameterPageBank;
import de.mossgrabers.framework.daw.midi.*;
import de.mossgrabers.framework.featuregroup.IMode;
import de.mossgrabers.framework.graphics.IRasterWritableBitmap;
import de.mossgrabers.framework.mode.Modes;
import de.mossgrabers.framework.observer.IBankPageObserver;
import de.mossgrabers.framework.usb.UsbException;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Real native coordinator + real display/mode manager; only Bitwig and USB endpoints are fakes. */
public final class NativeSamplerLensTest
{
    private static int checks;
    private static final List<IBankPageObserver> PAGE_OBSERVERS = new ArrayList<> ();
    private static int anchorSelections;

    public static void main (final String [] args) throws Exception
    {
        final IHwSurfaceFactory factory = proxy (IHwSurfaceFactory.class, (p, m, a) -> m.getName ().equals ("createButton") ? proxy (IHwButton.class, null) : null);
        final IRasterWritableBitmap bitmap = proxy (IRasterWritableBitmap.class, null);
        final IHost host = proxy (IHost.class, (p, m, a) -> switch (m.getName ()) {
            case "createSurfaceFactory" -> factory;
            case "createBitmap" -> bitmap;
            case "createMemoryBlock" -> (IMemoryBlock) () -> ByteBuffer.allocate ((int) a[0]);
            case "getUsbDevice" -> throw new UsbException ("No physical USB in native coordinator test.");
            case "getName" -> "Generated test host";
            default -> fallback (m.getReturnType ());
        });
        final PushConfiguration configuration = new PushConfiguration (host, null, List.of (), PushVersion.VERSION_3);
        final Push2Display display = new Push2Display (host, 128, configuration);
        try
        {
            final PushControlSurface surface = new PushControlSurface (host, new PushColorManager (PushVersion.VERSION_3), configuration,
                proxy (IMidiOutput.class, null), proxy (IMidiInput.class, null));
            surface.addGraphicsDisplay (display);
            for (final Modes mode: new Modes [] { Modes.DEVICE_PARAMS, Modes.VOLUME, Modes.MASTER_TEMP })
                surface.getModeManager ().register (mode, proxy (IMode.class, null));
            final Cell exists = new Cell (true), plugin = new Cell (false), firstExists = new Cell (true), secondExists = new Cell (false);
            final Cell sameSampler = new Cell (true), sameEditor = new Cell (true), sameAnchor = new Cell (false);
            final Cell editorPinned = new Cell (false), trackPinned = new Cell (false), anchorPinned = new Cell (false), anchorExists = new Cell (false);
            final Device first = proxy (Device.class, (p, m, a) -> m.getName ().equals ("exists") ? firstExists.value : null);
            final Device second = proxy (Device.class, (p, m, a) -> m.getName ().equals ("exists") ? secondExists.value : null);
            final DeviceBank bank = proxy (DeviceBank.class, (p, m, a) -> m.getName ().equals ("getItemAt") ? ((int) a[0] == 0 ? first : second) : null);
            final PinnableCursorDevice editor = proxy (PinnableCursorDevice.class, (p, m, a) -> m.getName ().equals ("isPinned") ? editorPinned.value : null);
            final PinnableCursorDevice anchor = proxy (PinnableCursorDevice.class, (p, m, a) -> switch (m.getName ()) {
                case "isPinned" -> anchorPinned.value;
                case "exists" -> anchorExists.value;
                case "selectDevice" -> { anchorSelections++; yield null; }
                default -> throw new AssertionError ("Unexpected anchor operation " + m.getName ());
            });
            final PinnableCursorDevice controlled = proxy (PinnableCursorDevice.class, (p, m, a) -> switch (m.getName ()) {
                case "exists" -> exists.value;
                case "isPlugin" -> plugin.value;
                case "position" -> proxy (IntegerValue.class, null);
                case "createSiblingsDeviceBank" -> bank;
                case "createEqualsValue" -> a[0] == first ? sameSampler.value : a[0] == editor ? sameEditor.value : sameAnchor.value;
                default -> throw new AssertionError ("Unexpected controlled-device operation " + m.getName ());
            });
            final CursorTrack track = proxy (CursorTrack.class, (p, m, a) -> switch (m.getName ()) {
                case "isPinned" -> trackPinned.value;
                case "position" -> proxy (IntegerValue.class, null);
                case "createCursorDevice" -> a[0].equals ("PUSHWIG_LENS_INSTANCE") ? anchor : editor;
                default -> throw new AssertionError ("Unexpected editor-track operation " + m.getName ());
            });
            final ControllerHost nativeHost = proxy (ControllerHost.class, (p, m, a) -> switch (m.getName ()) {
                case "createCursorTrack" -> track;
                case "createBitwigDeviceMatcher" -> {
                    check (a[0].equals (UUID.fromString ("468bc14b-b2e7-45a1-9666-e83117fe404e")), "Uses actual native Sampler catalogue ID");
                    yield proxy (DeviceMatcher.class, null);
                }
                default -> throw new AssertionError ("Unexpected native host operation " + m.getName ());
            });
            final int [] pagePosition = { 0 };
            final IParameterPageBank pages = proxy (IParameterPageBank.class, (p, m, a) -> m.getName ().equals ("getSelectedItemPosition") ? pagePosition[0] : fallback (m.getReturnType ()));
            final IParameterBank parameters = proxy (IParameterBank.class, (p, m, a) -> {
                if (m.getName ().equals ("getPageBank")) return pages;
                if (m.getName ().equals ("addPageObserver")) PAGE_OBSERVERS.add ((IBankPageObserver) a[0]);
                return fallback (m.getReturnType ());
            });
            final NativeSamplerLens lens = new NativeSamplerLens (nativeHost, controlled, parameters, surface);
            surface.getModeManager ().setActive (Modes.VOLUME);
            lens.update ();
            check (!display.isSamplerPresentationRequested (), "Native Sampler selection alone does not replace Track/Mix");
            surface.getModeManager ().setActive (Modes.DEVICE_PARAMS);
            lens.update ();
            check (!display.isSamplerPresentationRequested () && anchorSelections == 1, "Waits for actual anchor equality; does not assume command completed");
            for (int i = 0; i < 100; i++) lens.update ();
            check (anchorSelections == 1, "No repeated anchor commands while waiting");
            anchorPinned.emit (true); anchorExists.emit (true); sameAnchor.emit (true);
            lens.update ();
            check (display.isSamplerPresentationRequested (), "Qualified same native/editor/anchored instance acquires");
            final Object original = session (display);
            lens.update ();
            check (original.equals (session (display)), "Stable context does not churn session IDs");
            surface.getModeManager ().setTemporary (Modes.MASTER_TEMP);
            check (!display.isSamplerPresentationRequested (), "Temporary mode revokes before next flush");
            surface.getModeManager ().restore (); lens.update ();
            check (display.isSamplerPresentationRequested () && !original.equals (session (display)), "Mode re-entry uses a new identity");
            for (final Cell invalidating: List.of (plugin, secondExists, editorPinned, trackPinned))
            {
                invalidating.emit (true); lens.update ();
                check (!display.isSamplerPresentationRequested (), "Plugin/ambiguity/pinned editor cannot claim this visual");
                invalidating.emit (false); lens.update ();
                check (display.isSamplerPresentationRequested (), "Qualified context can return after refusal");
            }
            for (final Cell required: List.of (exists, firstExists, sameSampler, sameEditor, sameAnchor))
            {
                final Object before = session (display);
                required.emit (false);
                check (!display.isSamplerPresentationRequested (), "Native authority loss revokes immediately in observer");
                lens.update ();
                check (!display.isSamplerPresentationRequested (), "Invalid identity remains semantic-only");
                required.emit (true); lens.update ();
                check (display.isSamplerPresentationRequested () && !before.equals (session (display)), "Confirmed identity reacquisition gets a new session");
            }
            final Object beforePage = session (display);
            for (final IBankPageObserver observer: PAGE_OBSERVERS) observer.pageAdjusted ();
            check (!display.isSamplerPresentationRequested (), "Page adjustment revokes old frame context");
            lens.update ();
            check (!beforePage.equals (session (display)), "New page does not reuse old context identity");
            final Object beforeNativePage = session (display);
            pagePosition[0] = 1;
            lens.update ();
            check (!beforeNativePage.equals (session (display)), "Native page-index change without wrapper page event renews context");
            lens.close (); lens.update ();
            check (!display.isSamplerPresentationRequested (), "Closed native owner cannot reacquire");
            System.out.println ("NativeSamplerLensTest: PASS (" + checks + " checks; API endpoint simulation, not physical native identity acceptance)");
        }
        finally { display.shutdown (); }
    }

    private static Object session (final Push2Display display) throws Exception
    {
        final Field field = Push2Display.class.getDeclaredField ("samplerSession");
        field.setAccessible (true);
        return field.get (display);
    }

    private static final class Cell
    {
        private boolean current;
        private final List<com.bitwig.extension.callback.BooleanValueChangedCallback> observers = new ArrayList<> ();
        private final SettableBooleanValue value;
        private Cell (final boolean initial)
        {
            this.current = initial;
            this.value = proxy (SettableBooleanValue.class, (p, m, a) -> {
                if (m.getName ().equals ("get")) return this.current;
                if (m.getName ().equals ("addValueObserver")) this.observers.add ((com.bitwig.extension.callback.BooleanValueChangedCallback) a[0]);
                // Native requests remain pending until the test explicitly delivers an observation.
                return fallback (m.getReturnType ());
            });
        }
        private void emit (final boolean value)
        {
            this.current = value;
            for (final var observer: this.observers) observer.valueChanged (value);
        }
    }

    private static void check (final boolean condition, final String message)
    {
        checks++;
        if (!condition) throw new AssertionError (message);
    }

    private static Object fallback (final Class<?> type)
    {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == String.class) return "";
        if (type == int [].class) return new int [] { 0, 0 };
        return null;
    }

    @SuppressWarnings ("unchecked")
    private static <T> T proxy (final Class<T> type, final InvocationHandler handler)
    {
        return (T) Proxy.newProxyInstance (type.getClassLoader (), new Class<?> [] { type }, (p, m, a) -> {
            if (m.getName ().equals ("equals")) return p == a[0];
            if (m.getName ().equals ("hashCode")) return System.identityHashCode (p);
            if (m.getName ().equals ("toString")) return type.getSimpleName ();
            return handler == null ? fallback (m.getReturnType ()) : handler.invoke (p, m, a);
        });
    }
}
