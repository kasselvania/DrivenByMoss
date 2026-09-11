// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1A frame-pipeline modification (c) 2026 Peter Kassel
// Pushwig V1B synthetic-overlay selection (c) 2026 Peter Kassel
// Pushwig V1C dynamic-local selection (c) 2026 Peter Kassel
// Pushwig V1D-1 local-raster selection (c) 2026 Peter Kassel
// Pushwig V1D-2 external-ingress selection (c) 2026 Peter Kassel
// Pushwig V5A ordinary external-ingress activation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.controller.ableton.push.PushConfiguration;
import de.mossgrabers.framework.controller.display.AbstractGraphicDisplay;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.graphics.DefaultGraphicsDimensions;
import de.mossgrabers.framework.graphics.IBitmap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;


/**
 * The display of Push 2.
 *
 * @author Jürgen Moßgraber
 */
public class Push2Display extends AbstractGraphicDisplay
{
    // Startup/shutdown ownership uses this monitor; frame publication uses only frameLock.
    private final Object                           frameLock = new Object ();
    private PushFramePipeline                      framePipeline;
    private PushwigExternalIngressActivation        externalIngressActivation;
    private final boolean                          externalIngressRequested;
    private boolean                                externalIngressStartupAttempted;
    private volatile boolean                       shutdownRequested;
    private final boolean                          redrawCurrentModel;
    private final PushUsbDisplay                   usbDisplay;
    private boolean                                isShutdown = false;
    private boolean                                samplerLensManaged;
    private UUID                                   samplerSession;
    private SamplerLensPresentation                samplerPresentation;
    private SamplerLensContextPublisher            samplerPublisher;


    /**
     * Constructor. 4 rows (0-3) with 4 blocks (0-3). Each block consists of 17 characters or 2
     * cells (0-7).
     *
     * @param host The host
     * @param maxParameterValue The maximum parameter value (upper bound)
     * @param configuration The Push configuration
     */
    public Push2Display (final IHost host, final int maxParameterValue, final PushConfiguration configuration)
    {
        super (host, configuration, new DefaultGraphicsDimensions (960, 160, maxParameterValue), "Push 2 Display");

        this.usbDisplay = new PushUsbDisplay (host);

        this.externalIngressRequested = configuration.isPushwigExternalRasterIngressEnabled ();
        final boolean syntheticOverlayEnabled = Boolean.getBoolean ("pushwig.syntheticOverlay");
        final boolean dynamicLocalVisualEnabled = Boolean.getBoolean ("pushwig.dynamicLocalVisual");
        final boolean dynamicLocalRasterEnabled = Boolean.getBoolean ("pushwig.dynamicLocalRaster");
        PushFramePipeline selectedPipeline;
        // This is fixed before any send, including one racing with startup publication.
        boolean selectedRedrawCurrentModel = this.externalIngressRequested;
        if (this.externalIngressRequested)
        {
            // Retain external precedence without creating authority during setup initialization.
            selectedPipeline = PassThroughPushFramePipeline.INSTANCE;
        }
        else if (dynamicLocalRasterEnabled)
        {
            selectedPipeline = new DynamicLocalRasterPushFramePipeline ();
            selectedRedrawCurrentModel = true;
            host.println ("Pushwig: startup dynamic local raster pipeline enabled.");
        }
        else if (dynamicLocalVisualEnabled)
        {
            selectedPipeline = new DynamicLocalPushFramePipeline ();
            selectedRedrawCurrentModel = true;
            host.println ("Pushwig: startup dynamic local visual pipeline enabled.");
        }
        else if (syntheticOverlayEnabled)
        {
            selectedPipeline = SyntheticOverlayPushFramePipeline.INSTANCE;
            host.println ("Pushwig: startup synthetic overlay pipeline enabled.");
        }
        else
            selectedPipeline = PassThroughPushFramePipeline.INSTANCE;
        this.framePipeline = selectedPipeline;
        this.redrawCurrentModel = selectedRedrawCurrentModel;
    }


    /**
     * Internal Push setup hook, called only after all existing startup operations succeed.
     * Public only because setup and display reside in different Java packages. A failed attempt
     * remains semantic-only until a new display lifecycle; repeated calls never retry or duplicate.
     */
    public synchronized void startExternalIngress ()
    {
        if (!this.externalIngressRequested || this.externalIngressStartupAttempted || this.shutdownRequested)
            return;
        this.externalIngressStartupAttempted = true;
        this.externalIngressActivation = PushwigExternalIngressActivation.start (this.host);
        if (this.externalIngressActivation != null)
        {
            synchronized (this.frameLock)
            {
                this.framePipeline = this.externalIngressActivation.getPipeline ();
                if (this.samplerLensManaged)
                    this.samplerPublisher = new SamplerLensContextPublisher (this.host, this.externalIngressActivation.getRendezvous (), this.samplerSession);
            }
        }
    }


    /** Internal setup hook: establish local composition ownership without starting any ingress. */
    public void manageSamplerLens ()
    {
        synchronized (this.frameLock)
        {
            this.samplerLensManaged = true;
        }
    }


    /** Native context owner acquires one session; repeated reads do not change its identity. */
    public void acquireSamplerContext ()
    {
        synchronized (this.frameLock)
        {
            if (!this.samplerLensManaged || this.shutdownRequested || this.isShutdown)
                return;
            final UUID disconnected = this.framePipeline instanceof final ExternalRasterPushFramePipeline external ? external.getReceiver ().getLastDisconnectedSession () : null;
            if (this.samplerSession != null && !this.samplerSession.equals (disconnected))
                return;
            this.samplerSession = UUID.randomUUID ();
            if (this.samplerPublisher != null)
                this.samplerPublisher.offer (this.samplerSession);
        }
    }


    /** Revoke locally before the producer can observe a mode/device change. No I/O here. */
    public void revokeSamplerContext ()
    {
        synchronized (this.frameLock)
        {
            this.samplerSession = null;
            this.samplerPresentation = null;
            if (this.samplerPublisher != null)
                this.samplerPublisher.offer (null);
        }
    }


    /** A fresh mode render must supply readouts for each send; retained old-mode data is not used. */
    public void prepareSamplerPresentation (final SamplerLensPresentation presentation)
    {
        synchronized (this.frameLock)
        {
            this.samplerPresentation = this.samplerSession == null ? null : presentation;
        }
    }


    public boolean isSamplerPresentationRequested ()
    {
        synchronized (this.frameLock)
        {
            return this.samplerLensManaged && this.samplerSession != null && !this.isShutdown;
        }
    }


    /** Only musical note feedback is suppressible; all ordinary notifications remain blocking. */
    @Override
    public void notifyPlayedChord (final String message)
    {
        synchronized (this.frameLock)
        {
            if (this.samplerLensManaged && this.samplerSession != null && this.samplerPublisher != null &&
                this.samplerPublisher.isAvailable () && !this.shutdownRequested && !this.isShutdown)
                return;
            this.notify (message);
        }
    }


    /** {@inheritDoc} */
    @Override
    public void notify (final String message)
    {
        if (message == null)
            return;
        this.host.showNotification (message);
        this.setNotificationMessage (message);
    }


    /** {@inheritDoc} */
    @Override
    public void shutdown ()
    {
        final PushwigExternalIngressActivation activation;
        synchronized (this)
        {
            if (this.shutdownRequested)
                return;
            this.shutdownRequested = true;
            this.revokeSamplerContext ();
            activation = this.externalIngressActivation;
            if (activation != null)
                activation.beginShutdown ();
        }

        this.setMessage (3, "Please start " + this.host.getName () + " to play...");
        this.send ();

        synchronized (this.frameLock)
        {
            this.isShutdown = true;
        }

        final ExecutorService executor = Executors.newSingleThreadExecutor ();
        executor.execute ( () -> {

            if (activation != null)
                activation.awaitShutdown ();
            if (this.samplerPublisher != null)
                this.samplerPublisher.close ();
            if (this.usbDisplay != null)
                this.usbDisplay.shutdown ();
            super.shutdown ();

        });
        executor.shutdown ();
        try
        {
            executor.awaitTermination (10, TimeUnit.SECONDS);
        }
        catch (final InterruptedException ex)
        {
            this.host.error ("Display shutdown interrupted.", ex);
            Thread.currentThread ().interrupt ();
        }
    }


    /** {@inheritDoc} */
    @Override
    protected void send (final IBitmap image)
    {
        synchronized (this.frameLock)
        {
            if (!this.isShutdown && this.usbDisplay != null)
            {
                final IBitmap outputFrame;
                if (this.samplerLensManaged && this.framePipeline instanceof final ExternalRasterPushFramePipeline external)
                {
                    // Played-note feedback is suppressed at its typed entry point. Every retained
                    // notification and modal overlay remains blocking; none is classified by text.
                    final boolean permitted = this.samplerSession != null && this.samplerPublisher != null && this.samplerPublisher.isAvailable () && !this.hasSemanticOverlay ();
                    outputFrame = external.processSampler (image, permitted ? this.samplerSession.getMostSignificantBits () : 0,
                        permitted ? this.samplerSession.getLeastSignificantBits () : 0, permitted ? this.samplerPresentation : null);
                }
                else
                    outputFrame = this.framePipeline.process (image);
                this.samplerPresentation = null;
                this.usbDisplay.send (outputFrame);
            }
        }
    }


    /** {@inheritDoc} */
    @Override
    protected boolean shouldRedrawCurrentModel ()
    {
        return this.redrawCurrentModel;
    }
}
