// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1D-1 local-raster composition (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.graphics.IBitmap;
import de.mossgrabers.framework.graphics.IRasterWritableBitmap;
import de.mossgrabers.framework.graphics.RasterPixelFormat;


/**
 * Bounded production proof of synchronous local raster composition. This generated lifecycle is
 * not an external-frame source or protocol.
 *
 * @author Peter Kassel
 */
final class DynamicLocalRasterPushFramePipeline implements PushFramePipeline
{
    static final int STATE_SMALL       = 0;
    static final int STATE_ODD_PADDED  = 1;
    static final int STATE_MEDIUM      = 2;
    static final int STATE_FULL        = 3;
    static final int STATE_REPLACEMENT = 4;
    static final int STATE_NONE        = 5;
    static final int STATE_STALE       = 6;
    static final int STATE_INVALID     = 7;
    static final int STATE_MALFORMED   = 8;

    private static final int SENDS_PER_STATE = 64;
    private static final int STATE_COUNT     = 9;
    private static final int CYCLE_SENDS     = STATE_COUNT * SENDS_PER_STATE;

    private static final byte [] SMALL       = createPattern (64, 16, 256, 16, 1);
    private static final byte [] ODD_PADDED  = createPattern (117, 37, 481, 19, 2);
    private static final byte [] MEDIUM      = createPattern (480, 80, 1920, 8, 3);
    private static final byte [] FULL        = createPattern (960, 160, 3840, 0, 4);
    private static final byte [] REPLACEMENT = createPattern (64, 16, 256, 4, 5);

    private int sendCount = 0;


    /** {@inheritDoc} */
    @Override
    public IBitmap process (final IBitmap semanticFrame)
    {
        final int state = this.currentState ();
        this.sendCount++;
        if (this.sendCount == CYCLE_SENDS)
            this.sendCount = 0;

        if (!(semanticFrame instanceof final IRasterWritableBitmap rasterBitmap))
            return semanticFrame;

        switch (state)
        {
            case STATE_SMALL:
                apply (rasterBitmap, SMALL, 16, 256, 16, 8, 64, 16);
                break;
            case STATE_ODD_PADDED:
                apply (rasterBitmap, ODD_PADDED, 19, 481, 48, 12, 117, 37);
                break;
            case STATE_MEDIUM:
                apply (rasterBitmap, MEDIUM, 8, 1920, 240, 40, 480, 80);
                break;
            case STATE_FULL:
                apply (rasterBitmap, FULL, 0, 3840, 0, 0, 960, 160);
                break;
            case STATE_REPLACEMENT:
                apply (rasterBitmap, REPLACEMENT, 4, 256, 872, 136, 64, 16);
                break;
            case STATE_MALFORMED:
                if (rasterBitmap.writeRasterRegion (RasterPixelFormat.OPAQUE_BGRA8888, SMALL, 16, 256, -1, 8, 64, 16))
                    throw new IllegalStateException ("Malformed local raster request was accepted.");
                break;
            default:
                // NONE, STALE and INVALID keep the freshly redrawn semantic frame unchanged.
                break;
        }

        return semanticFrame;
    }


    int currentState ()
    {
        return this.sendCount / SENDS_PER_STATE;
    }


    private static void apply (final IRasterWritableBitmap bitmap, final byte [] source, final int sourceOffset, final int sourceStride, final int destinationX, final int destinationY, final int width, final int height)
    {
        bitmap.writeRasterRegion (RasterPixelFormat.OPAQUE_BGRA8888, source, sourceOffset, sourceStride, destinationX, destinationY, width, height);
    }


    private static byte [] createPattern (final int width, final int height, final int stride, final int offset, final int variant)
    {
        final int length = offset + (height - 1) * stride + width * 4;
        final byte [] bytes = new byte [length];
        for (int index = 0; index < bytes.length; index++)
            bytes[index] = (byte) 0x5A;

        final int topBandHeight = Math.max (2, height / 5);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int red = (x * 255) / Math.max (1, width - 1);
                int green = (y * 255) / Math.max (1, height - 1);
                int blue = (variant * 37 + x * 3 + y * 5) & 0xFF;

                // Five top bars expose red, green, blue, white and black channel handling.
                if (y < topBandHeight)
                {
                    final int bar = x * 5 / width;
                    red = bar == 0 || bar == 3 ? 255 : 0;
                    green = bar == 1 || bar == 3 ? 255 : 0;
                    blue = bar == 2 || bar == 3 ? 255 : 0;
                }
                // Explicit asymmetric row and column markers expose orientation and skew.
                else if ((y + variant) % 11 == 0)
                {
                    red = 255;
                    green = 255;
                    blue = 0;
                }
                else if ((x + variant * 3) % 29 == 0)
                {
                    red = 255;
                    green = 0;
                    blue = 255;
                }

                if (x < 4 && y < 4)
                {
                    red = 255;
                    green = 0;
                    blue = 0;
                }
                else if (x >= width - 4 && y < 4)
                {
                    red = 0;
                    green = 255;
                    blue = 0;
                }
                else if (x < 4 && y >= height - 4)
                {
                    red = 0;
                    green = 0;
                    blue = 255;
                }
                else if (x >= width - 4 && y >= height - 4)
                {
                    red = 255;
                    green = 255;
                    blue = 255;
                }

                final int pixel = offset + y * stride + x * 4;
                bytes[pixel] = (byte) blue;
                bytes[pixel + 1] = (byte) green;
                bytes[pixel + 2] = (byte) red;
                bytes[pixel + 3] = (byte) 0xFF;
            }
        }

        return bytes;
    }
}
