// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.bitwig.controller.ableton.push;

import de.mossgrabers.bitwig.framework.BitwigSetupFactory;
import de.mossgrabers.bitwig.framework.configuration.SettingsUIImpl;
import de.mossgrabers.bitwig.framework.daw.HostImpl;
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
    protected IControllerSetup<PushControlSurface, PushConfiguration> getControllerSetup (final ControllerHost controllerHost)
    {
        // Diagnostic artifact only: observe the real Push setup, never a second controller.
        return new PushControllerSetup (new HostImpl (controllerHost), new BitwigSetupFactory (controllerHost), new SettingsUIImpl (controllerHost, controllerHost.getPreferences ()), new SettingsUIImpl (controllerHost, controllerHost.getDocumentState ()), PushVersion.VERSION_3)
        {
            private PushBindingTrace trace;

            @Override
            public void init ()
            {
                super.init ();
                this.trace = PushBindingTrace.attach (controllerHost, this);
            }

            @Override
            public void flush ()
            {
                super.flush ();
                if (this.trace != null)
                    this.trace.flush ();
            }

            @Override
            public void exit ()
            {
                if (this.trace != null)
                    this.trace.close ();
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
