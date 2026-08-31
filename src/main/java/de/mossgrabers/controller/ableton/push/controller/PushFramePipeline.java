// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1A frame-pipeline addition (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.graphics.IBitmap;


/**
 * Processes a complete semantic Push frame synchronously before display transport.
 *
 * @author Peter Kassel
 */
@FunctionalInterface
public interface PushFramePipeline
{
    /**
     * Process a complete semantic Push frame.
     *
     * @param semanticFrame The semantic frame
     * @return The frame to send
     */
    IBitmap process (IBitmap semanticFrame);
}
