// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.bitwig.controller.ableton.push;

import de.mossgrabers.bitwig.framework.BitwigSetupFactory;
import de.mossgrabers.bitwig.framework.configuration.SettingsUIImpl;
import de.mossgrabers.bitwig.framework.daw.HostImpl;
import de.mossgrabers.bitwig.framework.daw.data.CursorDeviceImpl;
import de.mossgrabers.bitwig.framework.extension.AbstractControllerExtensionDefinition;
import de.mossgrabers.controller.ableton.push.Push3ControllerDefinition;
import de.mossgrabers.controller.ableton.push.PushConfiguration;
import de.mossgrabers.controller.ableton.push.PushControllerSetup;
import de.mossgrabers.controller.ableton.push.PushVersion;
import de.mossgrabers.controller.ableton.push.controller.PushControlSurface;
import de.mossgrabers.framework.controller.IControllerSetup;

import com.bitwig.extension.controller.api.ControllerHost;


/**
 * Definition class for the Push 3 controller extension.
 *
 * @author Jürgen Moßgraber
 */
public class Push3ControllerExtensionDefinition extends AbstractControllerExtensionDefinition<PushControlSurface, PushConfiguration>
{
    /**
     * Constructor.
     */
    public Push3ControllerExtensionDefinition ()
    {
        super (new Push3ControllerDefinition ());
    }


    /** {@inheritDoc} */
    @Override
    protected IControllerSetup<PushControlSurface, PushConfiguration> getControllerSetup (final ControllerHost host)
    {
        final ControllerHost nativeHost = host;
        return new PushControllerSetup (new HostImpl (host), new BitwigSetupFactory (host), new SettingsUIImpl (host, host.getPreferences ()), new SettingsUIImpl (host, host.getDocumentState ()), PushVersion.VERSION_3)
        {
            private NativeSamplerLens samplerLens;

            @Override
            public void init ()
            {
                super.init ();
                if (this.getSurface ().getConfiguration ().isPushwigExternalRasterIngressEnabled ())
                {
                    try
                    {
                        this.samplerLens = new NativeSamplerLens (nativeHost, ((CursorDeviceImpl) this.getModel ().getCursorDevice ()).getCursorDevice (),
                            this.getModel ().getCursorDevice ().getParameterBank (), this.getSurface ());
                    }
                    catch (final RuntimeException ex)
                    {
                        // Native observation failure must not abort ordinary Push controls. The
                        // display was made semantic-only before this observer requested API objects.
                        this.host.error ("Pushwig Sampler identity unavailable; retaining semantics.", ex);
                    }
                }
            }

            @Override
            public void flush ()
            {
                if (this.samplerLens != null)
                    this.samplerLens.update ();
                super.flush ();
            }

            @Override
            public void exit ()
            {
                if (this.samplerLens != null)
                    this.samplerLens.close ();
                super.exit ();
            }
        };
    }


    /** {@inheritDoc} */
    @Override
    public boolean isUsingBetaAPI ()
    {
        return true;
    }
}
