// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1D-1 local-raster composition (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.bitwig.framework.graphics;

import de.mossgrabers.framework.graphics.IEncoder;
import de.mossgrabers.framework.graphics.IRasterWritableBitmap;
import de.mossgrabers.framework.graphics.IRenderer;
import de.mossgrabers.framework.graphics.RasterPixelFormat;

import com.bitwig.extension.api.MemoryBlock;
import com.bitwig.extension.api.graphics.Bitmap;
import com.bitwig.extension.api.graphics.BitmapFormat;
import com.bitwig.extension.api.graphics.GraphicsOutput.AntialiasMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;


/**
 * Implementation of a bitmap.
 *
 * @author Jürgen Moßgraber
 */
public final class BitmapImpl implements IRasterWritableBitmap
{
    private static final int BYTES_PER_PIXEL = 4;

    private final Bitmap     bitmap;
    private final ByteBuffer rasterBuffer;
    private final int        rasterWidth;
    private final int        rasterHeight;
    private final int        rasterRowStride;
    private final int        rasterMemorySize;

    private volatile Thread  rasterOwnerThread;


    /**
     * Constructor.
     *
     * @param bitmap The Bitwig bitmap
     */
    public BitmapImpl (final Bitmap bitmap)
    {
        this.bitmap = bitmap;

        ByteBuffer destination = null;
        int width = 0;
        int height = 0;
        int rowStride = 0;
        int memorySize = 0;

        if (bitmap != null)
        {
            try
            {
                final BitmapFormat format = bitmap.getFormat ();
                final int candidateWidth = bitmap.getWidth ();
                final int candidateHeight = bitmap.getHeight ();
                final long candidateRowStride = (long) candidateWidth * BYTES_PER_PIXEL;
                final long expectedSize = candidateRowStride * candidateHeight;

                if (format == BitmapFormat.ARGB32 && format.bytesPerPixel () == BYTES_PER_PIXEL && candidateWidth > 0 && candidateHeight > 0 && candidateRowStride <= Integer.MAX_VALUE && expectedSize > 0 && expectedSize <= Integer.MAX_VALUE)
                {
                    final MemoryBlock memoryBlock = bitmap.getMemoryBlock ();
                    if (memoryBlock != null && memoryBlock.size () == (int) expectedSize)
                    {
                        final ByteBuffer candidate = memoryBlock.createByteBuffer ();
                        if (candidate != null && !candidate.isReadOnly () && candidate.isDirect () && candidate.position () == 0 && candidate.limit () == (int) expectedSize && candidate.capacity () == (int) expectedSize && candidate.order () == ByteOrder.LITTLE_ENDIAN)
                        {
                            destination = candidate;
                            width = candidateWidth;
                            height = candidateHeight;
                            rowStride = (int) candidateRowStride;
                            memorySize = (int) expectedSize;
                        }
                    }
                }
            }
            catch (final RuntimeException ex)
            {
                // Raster composition is optional. Ordinary bitmap operations remain available.
                destination = null;
                width = 0;
                height = 0;
                rowStride = 0;
                memorySize = 0;
            }
        }

        this.rasterBuffer = destination;
        this.rasterWidth = width;
        this.rasterHeight = height;
        this.rasterRowStride = rowStride;
        this.rasterMemorySize = memorySize;
    }


    /**
     * Get the wrapped Bitwig bitmap.
     *
     * @return The bitmap
     */
    public Bitmap bitmap ()
    {
        return this.bitmap;
    }


    /** {@inheritDoc} */
    @Override
    public void setDisplayWindowTitle (final String title)
    {
        this.bitmap.setDisplayWindowTitle (title);
    }


    /** {@inheritDoc} */
    @Override
    public void showDisplayWindow ()
    {
        this.bitmap.showDisplayWindow ();
    }


    /** {@inheritDoc} */
    @Override
    public void render (final boolean enableAntialias, final IRenderer renderer)
    {
        this.bitmap.render (gc -> renderer.render (new GraphicsContextImpl (enableAntialias ? AntialiasMode.BEST : AntialiasMode.OFF, gc)));
    }


    /** {@inheritDoc} */
    @Override
    public void encode (final IEncoder encoder)
    {
        final ByteBuffer imageBuffer = this.bitmap.getMemoryBlock ().createByteBuffer ();
        encoder.encode (imageBuffer, this.bitmap.getWidth (), this.bitmap.getHeight ());
    }


    /** {@inheritDoc} */
    @Override
    public boolean writeRasterRegion (final RasterPixelFormat format, final byte [] source, final int sourceOffset, final int sourceStride, final int destinationX, final int destinationY, final int width, final int height)
    {
        if (!this.validateRequest (format, source, sourceOffset, sourceStride, destinationX, destinationY, width, height))
            return false;

        final Thread currentThread = Thread.currentThread ();
        final Thread ownerThread = this.rasterOwnerThread;
        if (ownerThread != null && ownerThread != currentThread)
            return false;

        final int rowBytes = width * BYTES_PER_PIXEL;
        if (!hasOpaqueAlpha (source, sourceOffset, sourceStride, rowBytes, height))
            return false;

        if (ownerThread == null && !this.bindRasterOwner (currentThread))
            return false;

        int sourceIndex = sourceOffset;
        int destinationIndex = destinationY * this.rasterRowStride + destinationX * BYTES_PER_PIXEL;
        for (int row = 0; row < height; row++)
        {
            this.rasterBuffer.put (destinationIndex, source, sourceIndex, rowBytes);
            if (row + 1 < height)
            {
                sourceIndex += sourceStride;
                destinationIndex += this.rasterRowStride;
            }
        }

        return true;
    }


    /** {@inheritDoc} */
    @Override
    public boolean equals (final Object object)
    {
        if (this == object)
            return true;
        if (!(object instanceof final BitmapImpl other))
            return false;
        return Objects.equals (this.bitmap, other.bitmap);
    }


    /** {@inheritDoc} */
    @Override
    public int hashCode ()
    {
        return Objects.hashCode (this.bitmap);
    }


    /** {@inheritDoc} */
    @Override
    public String toString ()
    {
        return "BitmapImpl[bitmap=" + this.bitmap + "]";
    }


    private boolean validateRequest (final RasterPixelFormat format, final byte [] source, final int sourceOffset, final int sourceStride, final int destinationX, final int destinationY, final int width, final int height)
    {
        if (this.rasterBuffer == null || format != RasterPixelFormat.OPAQUE_BGRA8888 || source == null || width <= 0 || height <= 0 || destinationX < 0 || destinationY < 0 || sourceOffset < 0)
            return false;

        final long rowBytes = (long) width * BYTES_PER_PIXEL;
        final long destinationRight = (long) destinationX + width;
        final long destinationBottom = (long) destinationY + height;
        if (rowBytes > Integer.MAX_VALUE || destinationRight > this.rasterWidth || destinationBottom > this.rasterHeight || sourceStride < rowBytes)
            return false;

        final long sourceEnd = (long) sourceOffset + (long) (height - 1) * sourceStride + rowBytes;
        if (sourceEnd > source.length)
            return false;

        final long destinationEnd = (destinationBottom - 1) * this.rasterRowStride + destinationRight * BYTES_PER_PIXEL;
        return destinationEnd <= this.rasterMemorySize && destinationEnd <= this.rasterBuffer.limit () && destinationEnd <= this.rasterBuffer.capacity ();
    }


    private static boolean hasOpaqueAlpha (final byte [] source, final int sourceOffset, final int sourceStride, final int rowBytes, final int height)
    {
        int rowStart = sourceOffset;
        for (int row = 0; row < height; row++)
        {
            final int rowEnd = rowStart + rowBytes;
            for (int alphaIndex = rowStart + 3; alphaIndex < rowEnd; alphaIndex += BYTES_PER_PIXEL)
            {
                if (source[alphaIndex] != (byte) 0xFF)
                    return false;
            }
            if (row + 1 < height)
                rowStart += sourceStride;
        }
        return true;
    }


    private synchronized boolean bindRasterOwner (final Thread currentThread)
    {
        if (this.rasterOwnerThread == null)
        {
            this.rasterOwnerThread = currentThread;
            return true;
        }
        return this.rasterOwnerThread == currentThread;
    }
}
