// Pushwig binding observation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt
package de.mossgrabers.bitwig.controller.ableton.push;

import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.RelativeHardwareKnob;
import com.bitwig.extension.controller.api.RemoteControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.mossgrabers.bitwig.framework.daw.data.ParameterImpl;
import de.mossgrabers.bitwig.framework.hardware.HwRelativeKnobImpl;
import de.mossgrabers.controller.ableton.push.PushControllerSetup;
import de.mossgrabers.framework.controller.ContinuousID;
import de.mossgrabers.framework.daw.IModel;
import de.mossgrabers.framework.featuregroup.IMode;
import de.mossgrabers.framework.featuregroup.ModeManager;
import de.mossgrabers.framework.parameter.IParameter;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bounded diagnostic, not a product context protocol. Observes existing API objects only.
 * Never calls setBinding, setIndication, touch, reset, set, or selection operations.
 * API callbacks are ordered observations, NOT atomic assignment revisions or target UUIDs.
 */
final class PushBindingTrace implements AutoCloseable
{
    static final int MAX_RECORDS = 6000;
    private final ObjectMapper json = new ObjectMapper ();
    private final IModel model;
    private final ModeManager modes;
    private final RelativeHardwareKnob [] knobs;
    private final RemoteControl [] remotes;
    private final Consumer<String> output;
    private final Runnable finish;
    private int records;
    private boolean closed;
    private long nextSnapshot;
    private String previousSnapshot = "";

    static PushBindingTrace attach (final ControllerHost host, final PushControllerSetup setup)
    {
        BufferedWriter writer = null;
        try
        {
            final Path path = Files.createTempFile ("pushwig-binding-trace-", ".jsonl",
                PosixFilePermissions.asFileAttribute (PosixFilePermissions.fromString ("rw-------")));
            writer = Files.newBufferedWriter (path, StandardCharsets.UTF_8);
            final BufferedWriter sink = writer;
            final RelativeHardwareKnob [] knobs = new RelativeHardwareKnob [9];
            final RemoteControl [] remotes = new RemoteControl [8];
            for (int i = 0; i < 9; i++)
            {
                final ContinuousID id = i == 8 ? ContinuousID.MASTER_KNOB : ContinuousID.get (ContinuousID.KNOB1, i);
                knobs[i] = ((HwRelativeKnobImpl) setup.getSurface ().getContinuous (id)).getHardwareKnob ();
                if (i < 8)
                    remotes[i] = (RemoteControl) ((ParameterImpl) setup.getModel ().getCursorDevice ().getParameterBank ().getItem (i)).getParameter ();
            }
            final PushBindingTrace trace = new PushBindingTrace (setup.getModel (), setup.getSurface ().getModeManager (), knobs, remotes,
                line -> {
                    try { sink.write (line); sink.newLine (); }
                    catch (final java.io.IOException ex) { throw new IllegalStateException ("Trace write failed", ex); }
                }, () -> {
                    try { sink.close (); }
                    catch (final java.io.IOException ex) { host.println ("Pushwig binding trace close failed"); }
                });
            host.println ("Pushwig diagnostic binding trace: " + path + "; at most " + MAX_RECORDS + " records. No binding changes.");
            return trace;
        }
        catch (final Exception ex)
        {
            if (writer != null)
                try { writer.close (); } catch (final java.io.IOException ignored) { /* Diagnostic disabled. */ }
            host.println ("Pushwig binding trace disabled: " + ex.getClass ().getSimpleName ());
            return null;
        }
    }

    // Package-visible API-boundary constructor is also exercised by the deterministic test.
    PushBindingTrace (final IModel model, final ModeManager modes, final RelativeHardwareKnob [] knobs,
                      final RemoteControl [] remotes, final Consumer<String> output, final Runnable finish)
    {
        if (knobs.length != 9 || remotes.length != 8)
            throw new IllegalArgumentException ("Exactly eight row knobs plus master and eight remote slots");
        this.model = model;
        this.modes = modes;
        this.knobs = knobs.clone ();
        this.remotes = remotes.clone ();
        this.output = output;
        this.finish = finish;
        this.event ("start", 0, "observation-only; no atomic mapping-revision claim");
        modes.addChangeListener ((before, after) -> this.event ("mode", 0, String.valueOf (before) + " -> " + after));
        for (int i = 0; i < knobs.length; i++)
        {
            final int slot = i + 1;
            final RelativeHardwareKnob knob = knobs[i];
            knob.isBeingTouched ().markInterested ();
            knob.isUpdatingTargetValue ().markInterested ();
            knob.hasTargetValue ().markInterested ();
            knob.isBeingTouched ().addValueObserver (value -> this.event ("touch", slot, value));
            knob.targetValue ().addValueObserver (value -> this.event ("hardware-value", slot,
                Map.of ("value", value, "causedByThisControl", knob.isUpdatingTargetValue ().get ())));
            if (i < 8)
            {
                remotes[i].isBeingMapped ().markInterested ();
                remotes[i].isBeingMapped ().addValueObserver (value -> this.event ("mapping", slot, value));
            }
        }
    }

    /** At most ten snapshot comparisons/second; touch/value/mapping events are separate. */
    void flush ()
    {
        if (this.closed || System.nanoTime () < this.nextSnapshot)
            return;
        this.nextSnapshot = System.nanoTime () + 100_000_000L;
        try
        {
            final Map<String, Object> state = this.snapshot ();
            final String snapshot = this.json.writeValueAsString (state);
            if (!snapshot.equals (this.previousSnapshot))
            {
                this.previousSnapshot = snapshot;
                this.event ("snapshot", 0, state);
            }
        }
        catch (final Exception ex) { this.close (); }
    }

    Map<String, Object> snapshot ()
    {
        final var device = this.model.getCursorDevice ();
        final var pages = device.getParameterBank ().getPageBank ();
        final IMode mode = this.modes.getActive ();
        final Map<String, Object> result = new LinkedHashMap<> ();
        result.put ("mode", String.valueOf (this.modes.getActiveID ()));
        result.put ("temporaryMode", this.modes.isTemporary ());
        result.put ("trackPosition", this.model.getCursorTrack ().getPosition ());
        result.put ("devicePosition", device.getPosition ());
        result.put ("deviceName", bounded (device.getName ()));
        result.put ("deviceExists", device.doesExist ());
        result.put ("devicePinned", device.isPinned ());
        result.put ("pageIndex", pages.getSelectedItemIndex ());
        result.put ("pageName", bounded (pages.getSelectedItem ().orElse ("")));
        final List<Object> slots = new ArrayList<> (8);
        for (int i = 0; i < 8; i++)
        {
            final RemoteControl remote = this.remotes[i];
            final RelativeHardwareKnob knob = this.knobs[i];
            final Map<String, Object> slot = new LinkedHashMap<> ();
            slot.put ("encoder", i + 1);
            slot.put ("remoteExists", remote.exists ().get ());
            slot.put ("remoteName", bounded (remote.name ().get ()));
            slot.put ("remoteValue", remote.value ().get ());
            slot.put ("remoteDisplay", bounded (remote.displayedValue ().get ()));
            slot.put ("remoteModulatedValue", remote.modulatedValue ().get ());
            slot.put ("mappingInProgress", remote.isBeingMapped ().get ());
            slot.put ("hardwareTouched", knob.isBeingTouched ().get ());
            slot.put ("modeTouched", mode != null && mode.isKnobTouched (i));
            slot.put ("hardwareHasTarget", knob.hasTargetValue ().get ());
            slot.put ("hardwareName", bounded (knob.targetName ().get ()));
            slot.put ("hardwareValue", knob.targetValue ().get ());
            slot.put ("hardwareDisplay", bounded (knob.targetDisplayedValue ().get ()));
            slot.put ("hardwareModulatedValue", knob.modulatedTargetValue ().get ());
            int remoteSlot = 0;
            if (mode != null && mode.getParameterProvider () != null && i < mode.getParameterProvider ().size ())
            {
                final IParameter bound = mode.getParameterProvider ().get (i);
                if (bound instanceof final ParameterImpl param)
                    for (int j = 0; j < 8; j++)
                        if (param.getParameter () == this.remotes[j])
                            remoteSlot = j + 1;
            }
            // This is the MODE'S requested remote proxy, not the underlying native target identity.
            slot.put ("modeRequestedRemoteSlot", remoteSlot);
            slots.add (slot);
        }
        result.put ("slots", slots);
        result.put ("masterTouched", this.knobs[8].isBeingTouched ().get ());
        return result;
    }

    private void event (final String kind, final int slot, final Object value)
    {
        if (this.closed)
            return;
        try
        {
            final Map<String, Object> event = new LinkedHashMap<> ();
            event.put ("record", ++this.records);
            event.put ("utcMillis", System.currentTimeMillis ());
            event.put ("nanoTime", System.nanoTime ());
            event.put ("kind", kind);
            event.put ("slot", slot);
            event.put ("value", value);
            this.output.accept (this.json.writeValueAsString (event));
            if (this.records >= MAX_RECORDS - 1)
            {
                this.output.accept ("{\"kind\":\"record-limit\",\"truncated\":true}");
                this.close ();
            }
        }
        catch (final Exception ex) { this.close (); }
    }

    static String bounded (final String value)
    {
        return value == null ? "" : value.substring (0, Math.min (value.length (), 128));
    }

    @Override
    public void close ()
    {
        if (this.closed)
            return;
        this.closed = true;
        try { this.finish.run (); } catch (final Exception ignored) { /* Never disrupt Push shutdown. */ }
    }
}
