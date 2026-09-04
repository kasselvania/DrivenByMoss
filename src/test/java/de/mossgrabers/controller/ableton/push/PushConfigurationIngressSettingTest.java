// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V5A ordinary external-ingress activation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push;

import de.mossgrabers.framework.configuration.IEnumSetting;
import de.mossgrabers.framework.configuration.ISettingsUI;
import de.mossgrabers.framework.controller.valuechanger.IValueChanger;
import de.mossgrabers.framework.daw.IHost;

import java.lang.reflect.Proxy;
import java.util.List;


/** Deterministic contract test for the restart-scoped Pushwig preference. */
public final class PushConfigurationIngressSettingTest
{
    private PushConfigurationIngressSettingTest ()
    {
        // Utility class.
    }


    public static void main (final String [] arguments)
    {
        testModernPushReadsPersistedOn ();
        testModernPushDefaultsOff ();
        testPushOneDoesNotRegisterTheSetting ();
        System.out.println ("PushConfigurationIngressSettingTest: PASS");
    }


    private static void testModernPushReadsPersistedOn ()
    {
        final SettingCapture capture = new SettingCapture ("On");
        final PushConfiguration configuration = newConfiguration (PushVersion.VERSION_3);
        configuration.activatePushwigSettings (capture.settings ());
        require (configuration.isPushwigExternalRasterIngressEnabled (), "Persisted On was not captured.");
        capture.requireExpectedRegistration ();
    }


    private static void testModernPushDefaultsOff ()
    {
        final SettingCapture capture = new SettingCapture ("Off");
        final PushConfiguration configuration = newConfiguration (PushVersion.VERSION_2);
        configuration.activatePushwigSettings (capture.settings ());
        require (!configuration.isPushwigExternalRasterIngressEnabled (), "Off did not remain disabled.");
        capture.requireExpectedRegistration ();
    }


    private static void testPushOneDoesNotRegisterTheSetting ()
    {
        final SettingCapture capture = new SettingCapture ("On");
        final PushConfiguration configuration = newConfiguration (PushVersion.VERSION_1);
        configuration.activatePushwigSettings (capture.settings ());
        require (!configuration.isPushwigExternalRasterIngressEnabled (), "Push 1 unexpectedly enabled ingress.");
        require (capture.calls == 0, "Push 1 unexpectedly registered the Pushwig setting.");
    }


    private static PushConfiguration newConfiguration (final PushVersion version)
    {
        final IHost host = proxy (IHost.class);
        final IValueChanger valueChanger = proxy (IValueChanger.class);
        return new PushConfiguration (host, valueChanger, List.of (), version);
    }


    @SuppressWarnings("unchecked")
    private static <T> T proxy (final Class<T> type)
    {
        return (T) Proxy.newProxyInstance (type.getClassLoader (), new Class []
        {
            type
        }, (proxy, method, arguments) -> defaultValue (method.getReturnType ())) ;
    }


    private static Object defaultValue (final Class<?> type)
    {
        if (!type.isPrimitive ())
            return null;
        if (type == boolean.class)
            return Boolean.FALSE;
        if (type == char.class)
            return Character.valueOf ('\0');
        if (type == double.class)
            return Double.valueOf (0);
        if (type == float.class)
            return Float.valueOf (0);
        if (type == long.class)
            return Long.valueOf (0);
        return Integer.valueOf (0);
    }


    private static void require (final boolean condition, final String message)
    {
        if (!condition)
            throw new AssertionError (message);
    }


    private static final class SettingCapture
    {
        private final String value;
        private int          calls;
        private String       label;
        private String       category;
        private String []    options;
        private String       initial;


        private SettingCapture (final String value)
        {
            this.value = value;
        }


        private ISettingsUI settings ()
        {
            return (ISettingsUI) Proxy.newProxyInstance (ISettingsUI.class.getClassLoader (), new Class []
            {
                ISettingsUI.class
            }, (proxy, method, arguments) -> {

                if (!"getEnumSetting".equals (method.getName ()))
                    return defaultValue (method.getReturnType ());
                this.calls++;
                this.label = (String) arguments[0];
                this.category = (String) arguments[1];
                this.options = ((String []) arguments[2]).clone ();
                this.initial = (String) arguments[3];
                return Proxy.newProxyInstance (IEnumSetting.class.getClassLoader (), new Class []
                {
                    IEnumSetting.class
                }, (setting, settingMethod, settingArguments) -> "get".equals (settingMethod.getName ()) ? this.value : defaultValue (settingMethod.getReturnType ())) ;

            });
        }


        private void requireExpectedRegistration ()
        {
            require (this.calls == 1, "The Pushwig setting was not registered exactly once.");
            require ("External visual ingress (requires restart)".equals (this.label), "Unexpected setting label.");
            require ("Pushwig".equals (this.category), "Unexpected setting category.");
            require (this.options.length == 2 && "Off".equals (this.options[0]) && "On".equals (this.options[1]), "Unexpected setting options.");
            require ("Off".equals (this.initial), "The setting is not default-off.");
        }
    }
}
