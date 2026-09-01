// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1D-1 local-raster composition (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.framework.graphics;


/**
 * Optional host-neutral capability for applying an already-sized opaque raster region to a bitmap.
 *
 * @author Peter Kassel
 */
public interface IRasterWritableBitmap extends IBitmap
{
    /**
     * Apply one raster region synchronously and atomically with respect to validation. Source rows
     * run from top to bottom and columns from left to right. {@code sourceOffset} and
     * {@code sourceStride} are byte counts; the offset identifies the blue byte of the first source
     * pixel and the stride identifies the distance between successive source rows. The accepted
     * format stores blue, green, red, and alpha bytes in that order and requires every copied alpha
     * byte to be {@code 0xFF}. The operation performs no scaling, blending, filtering, or color
     * conversion.
     * <p>
     * The caller must retain exclusive ownership of {@code source} until this synchronous call
     * returns. The implementation does not retain the source array. A {@code true} result means
     * every requested destination pixel was applied; {@code false} means no destination byte was
     * changed.
     *
     * @param format The source pixel format
     * @param source The caller-owned source bytes
     * @param sourceOffset The byte offset of the first source pixel
     * @param sourceStride The number of source bytes between successive rows
     * @param destinationX The destination X coordinate
     * @param destinationY The destination Y coordinate
     * @param width The region width in pixels
     * @param height The region height in pixels
     * @return True if the complete region was applied; false if it was rejected without changing a
     *         destination byte
     */
    boolean writeRasterRegion (RasterPixelFormat format, byte [] source, int sourceOffset, int sourceStride, int destinationX, int destinationY, int width, int height);
}
