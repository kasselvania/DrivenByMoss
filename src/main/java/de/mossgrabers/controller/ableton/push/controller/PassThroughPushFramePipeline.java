// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1A frame-pipeline addition (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.graphics.IBitmap;


/**
 * A synchronous Push frame pipeline which returns the semantic frame unchanged.
 *
 * @author Peter Kassel
 */
public final class PassThroughPushFramePipeline implements PushFramePipeline
{
    /** The shared pass-through pipeline. */
    public static final PassThroughPushFramePipeline INSTANCE = new PassThroughPushFramePipeline ();


    private PassThroughPushFramePipeline ()
    {
        // Intentionally empty.
    }


    /** {@inheritDoc} */
    @Override
    public IBitmap process (final IBitmap semanticFrame)
    {
        return semanticFrame;
    }
}
