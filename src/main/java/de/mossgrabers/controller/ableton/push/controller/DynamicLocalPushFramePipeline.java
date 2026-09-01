// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1C dynamic-local composition (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.controller.color.ColorEx;
import de.mossgrabers.framework.graphics.IBitmap;
import de.mossgrabers.framework.graphics.IRenderer;


/**
 * A bounded local lifecycle proof for current-frame restoration and composition. This is not the
 * external visual compositor.
 *
 * @author Peter Kassel
 */
final class DynamicLocalPushFramePipeline implements PushFramePipeline
{
    static final int STATE_A       = 0;
    static final int STATE_B       = 1;
    static final int STATE_C       = 2;
    static final int STATE_D       = 3;
    static final int STATE_NONE    = 4;
    static final int STATE_STALE   = 5;
    static final int STATE_INVALID = 6;

    private static final int STATE_COUNT     = 7;
    private static final int SENDS_PER_STATE = 64;
    private static final int CYCLE_SENDS     = STATE_COUNT * SENDS_PER_STATE;

    private static final IRenderer RENDERER_A = gc -> {
        gc.fillRectangle (16, 4, 64, 16, ColorEx.RED);
        gc.fillRectangle (20, 8, 56, 8, ColorEx.WHITE);
    };

    private static final IRenderer RENDERER_B = gc -> {
        gc.fillRectangle (48, 8, 96, 24, ColorEx.ORANGE);
        gc.fillRectangle (52, 12, 88, 16, ColorEx.WHITE);
    };

    private static final IRenderer RENDERER_C = gc -> {
        gc.fillRectangle (320, 112, 48, 12, ColorEx.GREEN);
        gc.fillRectangle (324, 116, 40, 4, ColorEx.WHITE);
    };

    private static final IRenderer RENDERER_D = gc -> {
        gc.fillRectangle (808, 64, 136, 28, ColorEx.BLUE);
        gc.fillRectangle (812, 68, 128, 8, ColorEx.WHITE);
        gc.fillRectangle (840, 80, 72, 8, ColorEx.YELLOW);
    };

    private int sendCount = 0;


    /** {@inheritDoc} */
    @Override
    public IBitmap process (final IBitmap semanticFrame)
    {
        final int state = this.currentState ();
        this.sendCount++;
        if (this.sendCount == CYCLE_SENDS)
            this.sendCount = 0;

        switch (state)
        {
            case STATE_A:
                semanticFrame.render (false, RENDERER_A);
                break;
            case STATE_B:
                semanticFrame.render (false, RENDERER_B);
                break;
            case STATE_C:
                semanticFrame.render (false, RENDERER_C);
                break;
            case STATE_D:
                semanticFrame.render (false, RENDERER_D);
                break;
            default:
                // NONE, STALE and INVALID deliberately keep the current semantic frame unchanged.
                break;
        }

        return semanticFrame;
    }


    int currentState ()
    {
        return this.sendCount / SENDS_PER_STATE;
    }
}
