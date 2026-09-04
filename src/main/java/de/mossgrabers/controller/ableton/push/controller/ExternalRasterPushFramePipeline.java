// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1D-2 external-ingress implementation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.graphics.IBitmap;
import de.mossgrabers.framework.graphics.IRasterWritableBitmap;
import de.mossgrabers.framework.graphics.RasterPixelFormat;


/**
 * Synchronous display-thread consumer for the bounded external latest-frame ingress.
 *
 * @author Peter Kassel
 */
final class ExternalRasterPushFramePipeline implements PushFramePipeline
{
    private static final int MIN_PORT         = 1024;
    private static final int MAX_PORT         = 65535;
    private static final int MIN_STALE_MILLIS = 100;
    private static final int MAX_STALE_MILLIS = 10000;

    private final LatestExternalRasterFrameStore              store;
    private final LatestExternalRasterFrameStore.DisplayFrame displayFrame = new LatestExternalRasterFrameStore.DisplayFrame ();
    private final ExternalRasterReceiver                       receiver;

    private volatile boolean                                   closing;
    private volatile long                                      successfulApplications;


    private ExternalRasterPushFramePipeline (final LatestExternalRasterFrameStore store, final ExternalRasterReceiver receiver)
    {
        this.store = store;
        this.receiver = receiver;
    }


    static ExternalRasterPushFramePipeline create (final IHost host, final int port, final String tokenPath, final int staleMillis, final Runnable terminationCallback)
    {
        if (port < MIN_PORT || port > MAX_PORT || staleMillis < MIN_STALE_MILLIS || staleMillis > MAX_STALE_MILLIS || tokenPath == null || tokenPath.isBlank () || terminationCallback == null)
        {
            host.error ("Pushwig external raster ingress unavailable: invalid configuration.");
            return null;
        }

        final LatestExternalRasterFrameStore store = new LatestExternalRasterFrameStore ((long) staleMillis * 1_000_000L);
        final ExternalRasterReceiver receiver = ExternalRasterReceiver.start (host, store, port, tokenPath, terminationCallback);
        if (receiver == null)
        {
            store.close ();
            host.error ("Pushwig external raster ingress unavailable: private token file or loopback bind rejected.");
            return null;
        }

        return new ExternalRasterPushFramePipeline (store, receiver);
    }


    @Override
    public IBitmap process (final IBitmap semanticFrame)
    {
        if (this.closing || !this.store.tryAdopt (this.displayFrame, System.nanoTime ()))
            return semanticFrame;

        if (!(semanticFrame instanceof final IRasterWritableBitmap rasterBitmap))
        {
            this.store.rejectCurrent (this.displayFrame);
            return semanticFrame;
        }

        if (!rasterBitmap.writeRasterRegion (RasterPixelFormat.OPAQUE_BGRA8888, this.displayFrame.bytes, 0, this.displayFrame.sourceStride, this.displayFrame.destinationX, this.displayFrame.destinationY, this.displayFrame.width, this.displayFrame.height))
            this.store.rejectCurrent (this.displayFrame);
        else
            this.successfulApplications++;

        return semanticFrame;
    }


    void beginShutdown ()
    {
        this.closing = true;
        this.receiver.beginShutdown ();
    }


    void awaitShutdown ()
    {
        this.receiver.awaitShutdown ();
    }


    LatestExternalRasterFrameStore getStore ()
    {
        return this.store;
    }


    ExternalRasterReceiver getReceiver ()
    {
        return this.receiver;
    }


    LatestExternalRasterFrameStore.DisplayFrame getDisplayFrame ()
    {
        return this.displayFrame;
    }


    long getSuccessfulApplications ()
    {
        return this.successfulApplications;
    }
}
