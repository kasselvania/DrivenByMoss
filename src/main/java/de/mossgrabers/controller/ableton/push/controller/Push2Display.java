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
    private boolean                                shutdownRequested;
    private final boolean                          redrawCurrentModel;
    private final PushUsbDisplay                   usbDisplay;
    private boolean                                isShutdown = false;


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
            }
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
                final IBitmap outputFrame = this.framePipeline.process (image);
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
