// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V5A ordinary external-ingress activation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.daw.IHost;

import java.io.IOException;


/**
 * Owns the ordinary-launch external raster receiver and its private rendezvous for one Push display.
 *
 * @author Peter Kassel
 */
final class PushwigExternalIngressActivation
{
    enum State
    {
        ACTIVE,
        CLOSING,
        CLOSED
    }


    private final IHost                           host;
    private final PushwigRuntimeRendezvous        rendezvous;
    private final ExternalRasterPushFramePipeline pipeline;

    private volatile State                        state = State.ACTIVE;


    private PushwigExternalIngressActivation (final IHost host, final PushwigRuntimeRendezvous rendezvous, final ExternalRasterPushFramePipeline pipeline)
    {
        this.host = host;
        this.rendezvous = rendezvous;
        this.pipeline = pipeline;
    }


    static PushwigExternalIngressActivation start (final IHost host)
    {
        PushwigRuntimeRendezvous rendezvous = null;
        ExternalRasterPushFramePipeline pipeline = null;
        try
        {
            rendezvous = PushwigRuntimeRendezvous.prepare ();
            final PushwigRuntimeRendezvous ownedRendezvous = rendezvous;
            pipeline = ExternalRasterPushFramePipeline.create (host, PushwigRuntimeRendezvous.DEFAULT_PORT, rendezvous.getCapabilityPath ().toString (), PushwigRuntimeRendezvous.STALE_MILLIS, () -> {

                if (!ownedRendezvous.receiverTerminated ())
                    host.error ("Pushwig external raster receiver ended but its rendezvous cleanup was incomplete.");

            });
            if (pipeline == null)
                throw new IOException ("The existing external raster receiver did not start.");

            rendezvous.publish (pipeline.getReceiver ().getPort ());
            if (!pipeline.getReceiver ().isRunning ())
                throw new IOException ("The existing external raster receiver ended during activation.");

            host.println ("Pushwig: ordinary external visual ingress enabled on IPv4 loopback port " + pipeline.getReceiver ().getPort () + ".");
            return new PushwigExternalIngressActivation (host, rendezvous, pipeline);
        }
        catch (final IOException | RuntimeException ex)
        {
            if (pipeline != null)
            {
                pipeline.beginShutdown ();
                pipeline.awaitShutdown ();
            }
            if (rendezvous != null)
            {
                try
                {
                    rendezvous.close ();
                }
                catch (final IOException cleanupException)
                {
                    ex.addSuppressed (cleanupException);
                }
            }
            host.error ("Pushwig ordinary external visual ingress unavailable; the semantic display remains active.", ex);
            return null;
        }
    }


    ExternalRasterPushFramePipeline getPipeline ()
    {
        return this.pipeline;
    }


    PushwigRuntimeRendezvous getRendezvous ()
    {
        return this.rendezvous;
    }


    State getState ()
    {
        return this.state;
    }


    synchronized void beginShutdown ()
    {
        if (this.state != State.ACTIVE)
            return;

        this.state = State.CLOSING;
        if (!this.rendezvous.invalidateCurrentManifest ())
            this.host.error ("Pushwig could not remove the current rendezvous before receiver shutdown.");
        this.pipeline.beginShutdown ();
    }


    void awaitShutdown ()
    {
        this.beginShutdown ();
        this.pipeline.awaitShutdown ();
        try
        {
            this.rendezvous.close ();
        }
        catch (final IOException ex)
        {
            this.host.error ("Pushwig runtime cleanup was incomplete after receiver shutdown.", ex);
        }
        this.state = State.CLOSED;
    }
}
