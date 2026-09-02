// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1D-2 external-ingress implementation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import java.util.concurrent.locks.ReentrantLock;


/**
 * Fixed-memory latest-frame handoff between the external receiver and the Push display thread.
 *
 * @author Peter Kassel
 */
final class LatestExternalRasterFrameStore
{
    static final int MAX_PAYLOAD_BYTES = 960 * 160 * 4;

    private final ReentrantLock publicationLock = new ReentrantLock ();
    private final byte []       publishedBytes  = new byte [MAX_PAYLOAD_BYTES];
    private final long          staleNanos;

    private volatile long       authorityEpoch  = 1;
    private volatile long       latestAdoptedPublication;
    private volatile boolean    closed;

    private long                activeGeneration;
    private long                publicationVersion;
    private boolean             publishedValid;
    private int                 publishedLength;
    private int                 publishedStride;
    private int                 publishedX;
    private int                 publishedY;
    private int                 publishedWidth;
    private int                 publishedHeight;
    private long                publishedSequence;
    private long                publishedReceiptNanos;

    private volatile long       publishedFrames;
    private volatile long       adoptedFrames;
    private volatile long       supersededFrames;
    private volatile long       lockMisses;
    private volatile long       staleExpirations;
    private volatile long       clearOperations;
    private volatile long       writerRejections;


    /**
     * One display-thread-owned frame. The receiver never receives this object or its byte array.
     */
    static final class DisplayFrame
    {
        final byte [] bytes = new byte [MAX_PAYLOAD_BYTES];

        long          authorityEpoch;
        long          publicationVersion;
        long          rejectedPublicationVersion;
        long          sequence;
        long          receiptNanos;
        int           payloadLength;
        int           sourceStride;
        int           destinationX;
        int           destinationY;
        int           width;
        int           height;
        boolean       current;
        boolean       staleReported;
    }


    LatestExternalRasterFrameStore (final long staleNanos)
    {
        this.staleNanos = staleNanos;
    }


    void beginSession (final long generation)
    {
        if (this.closed)
            return;

        this.publicationLock.lock ();
        try
        {
            if (this.closed)
                return;
            this.activeGeneration = generation;
            this.invalidateLocked ();
        }
        finally
        {
            this.publicationLock.unlock ();
        }
    }


    boolean publish (final long generation, final byte [] source, final int payloadLength, final int sourceStride, final int destinationX, final int destinationY, final int width, final int height, final long sequence, final long receiptNanos)
    {
        if (this.closed || source == null || payloadLength < 1 || payloadLength > MAX_PAYLOAD_BYTES)
            return false;

        this.publicationLock.lock ();
        try
        {
            if (this.closed || generation == 0 || generation != this.activeGeneration)
                return false;

            if (this.publishedValid && this.publicationVersion > this.latestAdoptedPublication)
                this.supersededFrames++;

            System.arraycopy (source, 0, this.publishedBytes, 0, payloadLength);
            if (this.closed || generation != this.activeGeneration)
                return false;

            this.publishedLength = payloadLength;
            this.publishedStride = sourceStride;
            this.publishedX = destinationX;
            this.publishedY = destinationY;
            this.publishedWidth = width;
            this.publishedHeight = height;
            this.publishedSequence = sequence;
            this.publishedReceiptNanos = receiptNanos;
            this.publicationVersion++;
            this.publishedValid = true;
            this.publishedFrames++;
            return true;
        }
        finally
        {
            this.publicationLock.unlock ();
        }
    }


    void clear (final long generation)
    {
        this.publicationLock.lock ();
        try
        {
            if (!this.closed && generation != 0 && generation == this.activeGeneration)
            {
                this.invalidateLocked ();
                this.clearOperations++;
            }
        }
        finally
        {
            this.publicationLock.unlock ();
        }
    }


    void invalidateSession (final long generation)
    {
        this.publicationLock.lock ();
        try
        {
            if (generation != 0 && generation == this.activeGeneration)
            {
                this.activeGeneration = 0;
                this.invalidateLocked ();
            }
        }
        finally
        {
            this.publicationLock.unlock ();
        }
    }


    void close ()
    {
        // Shutdown and fatal-receiver invalidation must not wait for the publication lock on the
        // display/controller thread. The volatile closed gate and epoch revoke display authority.
        this.closed = true;
        this.authorityEpoch++;
    }


    boolean tryAdopt (final DisplayFrame frame, final long nowNanos)
    {
        this.synchronizeAuthority (frame);
        if (this.closed)
            return false;

        if (!this.publicationLock.tryLock ())
        {
            this.lockMisses++;
            this.synchronizeAuthority (frame);
            return !this.closed && this.isFresh (frame, nowNanos);
        }

        try
        {
            this.synchronizeAuthority (frame);
            if (!this.closed && this.publishedValid && this.publicationVersion > frame.publicationVersion && this.publicationVersion > frame.rejectedPublicationVersion)
            {
                System.arraycopy (this.publishedBytes, 0, frame.bytes, 0, this.publishedLength);
                frame.payloadLength = this.publishedLength;
                frame.sourceStride = this.publishedStride;
                frame.destinationX = this.publishedX;
                frame.destinationY = this.publishedY;
                frame.width = this.publishedWidth;
                frame.height = this.publishedHeight;
                frame.sequence = this.publishedSequence;
                frame.receiptNanos = this.publishedReceiptNanos;
                frame.publicationVersion = this.publicationVersion;
                frame.current = true;
                frame.staleReported = false;
                this.latestAdoptedPublication = this.publicationVersion;
                this.adoptedFrames++;
            }
        }
        finally
        {
            this.publicationLock.unlock ();
        }

        this.synchronizeAuthority (frame);
        return !this.closed && this.isFresh (frame, nowNanos);
    }


    void rejectCurrent (final DisplayFrame frame)
    {
        frame.current = false;
        frame.rejectedPublicationVersion = frame.publicationVersion;
        this.writerRejections++;
    }


    private void synchronizeAuthority (final DisplayFrame frame)
    {
        final long epoch = this.authorityEpoch;
        if (frame.authorityEpoch == epoch)
            return;

        frame.authorityEpoch = epoch;
        frame.current = false;
        frame.staleReported = false;
    }


    private boolean isFresh (final DisplayFrame frame, final long nowNanos)
    {
        if (!frame.current)
            return false;

        if (nowNanos - frame.receiptNanos <= this.staleNanos)
            return true;

        frame.current = false;
        if (!frame.staleReported)
        {
            frame.staleReported = true;
            this.staleExpirations++;
        }
        return false;
    }


    private void invalidateLocked ()
    {
        this.publishedValid = false;
        this.authorityEpoch++;
    }


    long getPublishedFrames ()
    {
        return this.publishedFrames;
    }


    long getAdoptedFrames ()
    {
        return this.adoptedFrames;
    }


    long getSupersededFrames ()
    {
        return this.supersededFrames;
    }


    long getLockMisses ()
    {
        return this.lockMisses;
    }


    long getStaleExpirations ()
    {
        return this.staleExpirations;
    }


    long getClearOperations ()
    {
        return this.clearOperations;
    }


    long getWriterRejections ()
    {
        return this.writerRejections;
    }
}
