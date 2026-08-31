// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1B synthetic-overlay addition (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.controller.color.ColorEx;
import de.mossgrabers.framework.graphics.IBitmap;
import de.mossgrabers.framework.graphics.IRenderer;


/**
 * A fixed diagnostic composition proof, not the final visual compositor.
 *
 * @author Peter Kassel
 */
public final class SyntheticOverlayPushFramePipeline implements PushFramePipeline
{
    /** The shared synthetic-overlay pipeline. */
    public static final SyntheticOverlayPushFramePipeline INSTANCE = new SyntheticOverlayPushFramePipeline ();

    private static final IRenderer RENDERER = gc -> {
        gc.fillRectangle (856, 4, 96, 16, ColorEx.PINK);
        gc.fillRectangle (860, 8, 88, 8, ColorEx.WHITE);
    };


    private SyntheticOverlayPushFramePipeline ()
    {
        // Intentionally empty.
    }


    /** {@inheritDoc} */
    @Override
    public IBitmap process (final IBitmap semanticFrame)
    {
        semanticFrame.render (false, RENDERER);
        return semanticFrame;
    }
}
