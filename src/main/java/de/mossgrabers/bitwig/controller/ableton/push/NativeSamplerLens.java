// Pushwig native Sampler context ownership (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.bitwig.controller.ableton.push;

import com.bitwig.extension.controller.api.BooleanValue;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorDeviceFollowMode;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.Device;
import com.bitwig.extension.controller.api.DeviceBank;
import com.bitwig.extension.controller.api.PinnableCursorDevice;

import de.mossgrabers.controller.ableton.push.controller.Push2Display;
import de.mossgrabers.controller.ableton.push.controller.PushControlSurface;
import de.mossgrabers.framework.daw.data.bank.IParameterBank;
import de.mossgrabers.framework.mode.Modes;

import java.util.UUID;


/**
 * Bitwig-native, initial single-Sampler envelope. A pinned observation cursor anchors the device
 * instance: replacing a same-named device at the same index is not merely a metadata comparison.
 * These cursors never bind encoders or select a device in the editor.
 */
final class NativeSamplerLens
{
    // Existing DrivenByMoss native-device catalogue; not a display-name match.
    private static final UUID SAMPLER_ID = UUID.fromString ("468bc14b-b2e7-45a1-9666-e83117fe404e");
    private final Push2Display display;
    private final PushControlSurface surface;
    private final IParameterBank parameters;
    private final PinnableCursorDevice controlled;
    private final PinnableCursorDevice anchor;
    private final CursorTrack editorTrack;
    private final PinnableCursorDevice editorDevice;
    private final Device first;
    private final Device second;
    private final BooleanValue sameNativeSampler;
    private final BooleanValue sameEditor;
    private final BooleanValue sameAnchor;
    private boolean anchorRequested;
    private boolean closed;
    private int pagePosition = Integer.MIN_VALUE;


    NativeSamplerLens (final ControllerHost host, final PinnableCursorDevice controlled, final IParameterBank parameters, final PushControlSurface surface)
    {
        this.surface = surface;
        this.parameters = parameters;
        this.display = (Push2Display) surface.getGraphicsDisplay ();
        this.display.manageSamplerLens ();
        this.controlled = controlled;
        this.editorTrack = host.createCursorTrack ("PUSHWIG_LENS_EDITOR", "Pushwig lens editor identity", 0, 0, true);
        this.editorDevice = this.editorTrack.createCursorDevice ("PUSHWIG_LENS_EDITOR_DEVICE", "Pushwig lens editor device", 0, CursorDeviceFollowMode.FOLLOW_SELECTION);
        this.anchor = this.editorTrack.createCursorDevice ("PUSHWIG_LENS_INSTANCE", "Pushwig lens instance anchor", 0, CursorDeviceFollowMode.FOLLOW_SELECTION);
        this.anchor.isPinned ().set (true);
        final DeviceBank samplers = this.controlled.createSiblingsDeviceBank (2);
        samplers.setDeviceMatcher (host.createBitwigDeviceMatcher (SAMPLER_ID));
        this.first = samplers.getItemAt (0);
        this.second = samplers.getItemAt (1);
        this.sameNativeSampler = this.controlled.createEqualsValue (this.first);
        this.sameEditor = this.controlled.createEqualsValue (this.editorDevice);
        this.sameAnchor = this.controlled.createEqualsValue (this.anchor);

        for (final BooleanValue value: new BooleanValue [] { this.controlled.exists (), this.controlled.isPlugin (),
            this.editorTrack.isPinned (), this.editorDevice.isPinned (), this.anchor.isPinned (), this.anchor.exists (),
            this.first.exists (), this.second.exists (), this.sameNativeSampler, this.sameEditor, this.sameAnchor })
        {
            value.markInterested ();
            value.addValueObserver (ignored -> this.invalidate ());
        }
        this.controlled.position ().markInterested ();
        this.controlled.position ().addValueObserver (ignored -> this.invalidate ());
        this.editorTrack.position ().markInterested ();
        this.editorTrack.position ().addValueObserver (ignored -> this.invalidate ());
        parameters.addPageObserver (this::invalidate);
        surface.getModeManager ().addChangeListener ((previous, current) -> this.invalidate ());
    }


    private void invalidate ()
    {
        this.display.revokeSamplerContext ();
        this.anchorRequested = false;
    }


    /** Called before the existing controller flush; only cached public API values are read. */
    void update ()
    {
        // The wrapper's page observer is not an observer of every native page-index change.
        // Read the existing interested native-backed index before composing this controller flush.
        final int currentPage = this.parameters.getPageBank ().getSelectedItemPosition ();
        if (currentPage != this.pagePosition)
        {
            this.pagePosition = currentPage;
            this.invalidate ();
        }
        if (this.closed || this.surface.getModeManager ().getActiveID () != Modes.DEVICE_PARAMS ||
            !this.controlled.exists ().get () || this.controlled.isPlugin ().get () ||
            !this.first.exists ().get () || this.second.exists ().get () || !this.sameNativeSampler.get () ||
            !this.sameEditor.get () || this.editorTrack.isPinned ().get () || this.editorDevice.isPinned ().get ())
        {
            this.display.revokeSamplerContext ();
            return;
        }

        if (!this.anchor.isPinned ().get () || !this.anchor.exists ().get () || !this.sameAnchor.get ())
        {
            this.display.revokeSamplerContext ();
            if (!this.anchorRequested)
            {
                this.anchorRequested = true;
                this.anchor.isPinned ().set (true);
                this.anchor.selectDevice (this.controlled);
            }
            return; // Never assume the asynchronous cursor request has already succeeded.
        }
        this.anchorRequested = false;
        this.display.acquireSamplerContext ();
    }


    void close ()
    {
        this.closed = true;
        this.display.revokeSamplerContext ();
    }
}
