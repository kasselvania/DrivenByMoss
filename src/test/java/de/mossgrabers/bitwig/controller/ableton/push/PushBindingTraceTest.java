// Pushwig diagnostic tests (c) 2026 Peter Kassel; LGPLv3.
package de.mossgrabers.bitwig.controller.ableton.push;

import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.mossgrabers.bitwig.framework.daw.data.ParameterImpl;
import de.mossgrabers.framework.controller.valuechanger.TwosComplementValueChanger;
import de.mossgrabers.framework.daw.IModel;
import de.mossgrabers.framework.daw.data.ICursorDevice;
import de.mossgrabers.framework.daw.data.ICursorTrack;
import de.mossgrabers.framework.daw.data.bank.IParameterBank;
import de.mossgrabers.framework.daw.data.bank.IParameterPageBank;
import de.mossgrabers.framework.featuregroup.IMode;
import de.mossgrabers.framework.featuregroup.ModeManager;
import de.mossgrabers.framework.mode.Modes;
import de.mossgrabers.framework.parameterprovider.IParameterProvider;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;

/** Tests the actual observer against strict read-only API proxies, not a second binding engine. */
public final class PushBindingTraceTest
{
    private static int checks;
    private final Map<String, Object> values = new HashMap<> ();
    private final Map<String, Object> nodes = new HashMap<> ();
    private final Map<String, List<Object>> callbacks = new HashMap<> ();
    private final List<String> lines = new ArrayList<> ();
    private final RelativeHardwareKnob [] knobs = new RelativeHardwareKnob[9];
    private final RemoteControl [] remotes = new RemoteControl[8];
    private final ParameterImpl [] parameters = new ParameterImpl[8];
    private final ModeManager modes = new ModeManager ();
    private final boolean [] touched = new boolean[8];
    private int page;
    private int ended;
    private final IModel model;

    private PushBindingTraceTest ()
    {
        for (int i = 0; i < 9; i++)
        {
            knobs[i] = this.node (RelativeHardwareKnob.class, "knob" + i);
            if (i < 8)
            {
                remotes[i] = this.node (RemoteControl.class, "remote" + i);
                parameters[i] = new ParameterImpl (new TwosComplementValueChanger (1024, 10), remotes[i], i, true);
            }
        }
        final IParameterPageBank pages = proxy (IParameterPageBank.class, (name, args) -> switch (name) {
            case "getSelectedItemIndex" -> this.page;
            case "getSelectedItem" -> Optional.of ("Page " + this.page);
            default -> fail (name);
        });
        final IParameterBank bank = proxy (IParameterBank.class, (name, args) -> name.equals ("getPageBank") ? pages : fail (name));
        final ICursorDevice device = proxy (ICursorDevice.class, (name, args) -> switch (name) {
            case "getParameterBank" -> bank;
            case "getName" -> "Sampler";
            case "getPosition" -> 2;
            case "doesExist" -> true;
            case "isPinned" -> false;
            default -> fail (name);
        });
        final ICursorTrack track = proxy (ICursorTrack.class, (name, args) -> name.equals ("getPosition") ? 3 : fail (name));
        this.model = proxy (IModel.class, (name, args) -> switch (name) {
            case "getCursorDevice" -> device;
            case "getCursorTrack" -> track;
            default -> fail (name);
        });
        final IParameterProvider provider = proxy (IParameterProvider.class, (name, args) -> switch (name) {
            case "size" -> 8;
            case "get" -> parameters[(Integer) args[0]];
            default -> fail (name);
        });
        this.modes.register (Modes.DEVICE_PARAMS, this.mode (provider));
        this.modes.register (Modes.MASTER_TEMP, this.mode (null));
        this.modes.setActive (Modes.DEVICE_PARAMS);
    }

    private IMode mode (final IParameterProvider provider)
    {
        return proxy (IMode.class, (name, args) -> switch (name) {
            case "isKnobTouched" -> this.touched[(Integer) args[0]];
            case "getParameterProvider" -> provider;
            case "onActivate", "onDeactivate" -> null;
            default -> fail (name);
        });
    }

    public static void main (final String [] args) throws Exception
    {
        final PushBindingTraceTest f = new PushBindingTraceTest ();
        final PushBindingTrace trace = new PushBindingTrace (f.model, f.modes, f.knobs, f.remotes, f.lines::add, () -> f.ended++);
        final ObjectMapper json = new ObjectMapper ();
        f.change ("remote0.name", "Speed");
        f.change ("remote0.displayedValue", "100 %");
        f.change ("knob0.targetDisplayedValue", "100 %");
        var snapshot = json.valueToTree (trace.snapshot ());
        check (snapshot.path ("slots").size () == 8, "eight real remote slots");
        check (snapshot.at ("/slots/0/modeRequestedRemoteSlot").asInt () == 1, "same API remote proxy identified, not label matched");
        check (snapshot.at ("/slots/0/remoteName").asText ().equals ("Speed"), "API remote name");
        f.change ("knob0.isBeingTouched", true);
        f.change ("knob1.isBeingTouched", true);
        f.touched[0] = f.touched[1] = true;
        snapshot = json.valueToTree (trace.snapshot ());
        check (snapshot.at ("/slots/0/hardwareTouched").asBoolean () && snapshot.at ("/slots/1/hardwareTouched").asBoolean (), "multiple hardware touches retained");
        check (snapshot.at ("/slots/0/modeTouched").asBoolean (), "mode touch distinct from raw hardware state");
        f.change ("knob0.isUpdatingTargetValue", true);
        f.change ("knob0.targetValue", 0.4);
        check (json.readTree (f.lines.getLast ()).at ("/value/causedByThisControl").asBoolean (), "encoder-caused value callback");
        f.change ("knob0.isUpdatingTargetValue", false);
        f.change ("knob0.targetValue", 0.5);
        check (!json.readTree (f.lines.getLast ()).at ("/value/causedByThisControl").asBoolean (), "other-source value callback, not falsely called mouse");
        f.change ("remote0.isBeingMapped", true);
        check (json.readTree (f.lines.getLast ()).path ("kind").asText ().equals ("mapping"), "mapping callback even with unchanged name and value");
        f.change ("remote0.isBeingMapped", false);
        f.page = 1;
        check (json.valueToTree (trace.snapshot ()).path ("pageIndex").asInt () == 1, "current page while held");
        f.modes.setTemporary (Modes.MASTER_TEMP);
        snapshot = json.valueToTree (trace.snapshot ());
        check (snapshot.path ("temporaryMode").asBoolean (), "temporary master context");
        check (snapshot.at ("/slots/0/modeRequestedRemoteSlot").asInt () == 0, "do not claim device binding in master mode");
        f.modes.restore ();
        f.change ("knob0.isBeingTouched", false);
        check (!json.readTree (f.lines.getLast ()).path ("value").asBoolean (), "release observed");
        f.change ("remote0.name", "renamed \"same\"\nlabel");
        trace.flush ();
        final int before = f.lines.size ();
        trace.flush ();
        check (f.lines.size () == before, "snapshot rate bounded");
        check (PushBindingTrace.bounded ("x".repeat (1000)).length () == 128, "text bounded");
        for (int i = 0; i < PushBindingTrace.MAX_RECORDS + 10; i++) f.change ("knob0.isBeingTouched", i % 2 == 0);
        check (f.lines.size () == PushBindingTrace.MAX_RECORDS, "strict record cap");
        check (json.readTree (f.lines.getLast ()).path ("truncated").asBoolean (), "cap explicitly reports truncation");
        trace.close (); trace.close ();
        check (f.ended == 1, "one close; later callbacks inert");
        for (final String line: f.lines) json.readTree (line);
        check (true, "all records valid JSON");
        final PushBindingTrace failed = new PushBindingTrace (f.model, f.modes, f.knobs, f.remotes,
            line -> { throw new IllegalStateException ("simulated I/O failure"); }, () -> f.ended++);
        failed.flush (); failed.close ();
        check (f.ended == 2, "diagnostic output failure disables observer without escaping");
        System.out.println ("PushBindingTraceTest: " + checks + " checks PASS; all unexpected API writes throw. Not a live mapping proof.");
    }

    private void change (final String key, final Object value)
    {
        this.values.put (key, value);
        for (final Object callback: this.callbacks.getOrDefault (key, List.of ()))
        {
            if (callback instanceof BooleanValueChangedCallback b) b.valueChanged ((Boolean) value);
            else if (callback instanceof DoubleValueChangedCallback d) d.valueChanged (((Number) value).doubleValue ());
            else if (callback instanceof StringValueChangedCallback s) s.valueChanged ((String) value);
            else throw new AssertionError ("Unexpected observer");
        }
    }

    private <T> T node (final Class<T> type, final String key)
    {
        if (this.nodes.containsKey (key)) return type.cast (this.nodes.get (key));
        final T result = type.cast (Proxy.newProxyInstance (type.getClassLoader (), new Class<?> [] {type}, (object, method, args) -> {
            final String name = method.getName ();
            if (name.equals ("markInterested")) return null;
            if (name.equals ("addValueObserver")) { this.callbacks.computeIfAbsent (key, k -> new ArrayList<> ()).add (args[args.length - 1]); return null; }
            if (name.equals ("get"))
            {
                final Class<?> valueType = method.getReturnType ();
                return this.values.getOrDefault (key, valueType == boolean.class ? false : valueType == double.class ? 0.0 : valueType == int.class ? 0 : "");
            }
            if (Set.of ("exists", "name", "value", "modulatedValue", "displayedValue", "discreteValueCount", "isBeingMapped",
                "isBeingTouched", "isUpdatingTargetValue", "hasTargetValue", "targetName", "targetValue", "targetDisplayedValue", "modulatedTargetValue").contains (name))
                return this.node (method.getReturnType (), key + "." + name);
            return fail ("Unexpected API operation: " + key + "." + name);
        }));
        this.nodes.put (key, result);
        return result;
    }

    private static <T> T proxy (final Class<T> type, final BiFunction<String, Object [], Object> implementation)
    {
        return type.cast (Proxy.newProxyInstance (type.getClassLoader (), new Class<?> [] {type},
            (object, method, args) -> implementation.apply (method.getName (), args)));
    }
    private static Object fail (final String name) { throw new AssertionError (name); }
    private static void check (final boolean result, final String label) { if (!result) throw new AssertionError (label); checks++; }
}
