// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V5A ordinary external-ingress activation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.IMemoryBlock;
import de.mossgrabers.framework.graphics.IRasterWritableBitmap;
import de.mossgrabers.framework.usb.UsbException;
import de.mossgrabers.controller.ableton.push.PushConfiguration;
import de.mossgrabers.controller.ableton.push.PushVersion;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


/** Deterministic process and protocol lifecycle tests against the production V1D-2 owners. */
public final class ExternalRasterIngressLifecycleTest
{
    private static final int          PORT   = PushwigRuntimeRendezvous.DEFAULT_PORT;
    private static final ObjectMapper MAPPER = new ObjectMapper ();


    private ExternalRasterIngressLifecycleTest ()
    {
        // Utility class.
    }


    public static void main (final String [] arguments) throws Exception
    {
        final String originalHome = System.getProperty ("user.home");
        final Path testHome = Files.createTempDirectory ("pushwig-v5a-lifecycle-");
        try
        {
            System.setProperty ("user.home", testHome.toString ());
            final HostLog hostLog = new HostLog ();
            testBindFailureCleanup (hostLog.host ());
            testFrameClearDisconnectAndRestart (hostLog);
            testDisplayStartupBoundary (hostLog);
            testSamplerContextLifecycle (hostLog);
            require (hostLog.messages.stream ().noneMatch (message -> message.matches (".*[0-9a-f]{64}.*")), "A capability value appeared in host output.");
            System.out.println ("ExternalRasterIngressLifecycleTest: PASS");
        }
        finally
        {
            if (originalHome == null)
                System.clearProperty ("user.home");
            else
                System.setProperty ("user.home", originalHome);
            deleteTree (testHome);
        }
    }


    private static void testBindFailureCleanup (final IHost host) throws Exception
    {
        try (ServerSocket collision = new ServerSocket ())
        {
            collision.bind (new InetSocketAddress (InetAddress.getByAddress (new byte []
            {
                127,
                0,
                0,
                1
            }), PORT));
            final PushwigExternalIngressActivation activation = PushwigExternalIngressActivation.start (host);
            require (activation == null, "A loopback bind collision did not fail semantic-only.");
            final Path root = runtimeRoot ();
            require (!Files.exists (root.resolve ("current.json"), LinkOption.NOFOLLOW_LINKS), "Bind failure published a current manifest.");
            require (capabilityCount (root) == 0, "Bind failure leaked a capability.");
        }
    }


    private static void testFrameClearDisconnectAndRestart (final HostLog hostLog) throws Exception
    {
        final PushwigExternalIngressActivation first = PushwigExternalIngressActivation.start (hostLog.host ());
        require (first != null, "First ordinary activation failed.");
        final Discovery firstDiscovery = discover ();
        final byte [] firstCapability = firstDiscovery.capability ();
        final String firstGeneration = firstDiscovery.generation ();
        require (firstDiscovery.port () == PORT, "Producer discovery returned the wrong port.");
        require (Files.exists (first.getRendezvous ().getManifestPath (), LinkOption.NOFOLLOW_LINKS), "Active receiver has no manifest.");
        require (isListenerReachable (), "Active receiver is not listening.");

        try (Socket socket = connect ())
        {
            authenticate (socket, firstCapability, 1, 2);
            final byte [] payload = new byte []
            {
                1,
                2,
                3,
                (byte) 0xFF,
                4,
                5,
                6,
                (byte) 0xFF,
                7,
                8,
                9,
                (byte) 0xFF,
                10,
                11,
                12,
                (byte) 0xFF
            };
            sendFrame (socket, 1, 2, 1, 7, 9, 2, 2, 8, payload);
            waitFor (() -> first.getPipeline ().getReceiver ().getAcceptedFrames () == 1, "Frame was not accepted.");

            final LatestExternalRasterFrameStore.DisplayFrame frame = new LatestExternalRasterFrameStore.DisplayFrame ();
            require (first.getPipeline ().getStore ().tryAdopt (frame, System.nanoTime ()), "Published frame was not adoptable.");
            require (frame.destinationX == 7 && frame.destinationY == 9 && frame.width == 2 && frame.height == 2 && frame.sourceStride == 8, "Published frame metadata changed.");
            require (Arrays.equals (payload, Arrays.copyOf (frame.bytes, payload.length)), "Published frame payload changed.");

            sendClear (socket, 1, 2, 2);
            waitFor (() -> first.getPipeline ().getStore ().getClearOperations () == 1, "CLEAR was not processed.");
            require (!first.getPipeline ().getStore ().tryAdopt (frame, System.nanoTime ()), "CLEAR did not revoke publication.");
        }
        waitFor (() -> first.getPipeline ().getReceiver ().getDisconnects () == 1, "Disconnect did not invalidate the session.");

        final long wrongBefore = first.getPipeline ().getReceiver ().getAuthenticationRejects ();
        final byte [] wrongCapability = firstCapability.clone ();
        wrongCapability[0] ^= 0x5A;
        sendHelloAndClose (wrongCapability, 3, 4);
        waitFor (() -> first.getPipeline ().getReceiver ().getAuthenticationRejects () > wrongBefore, "Wrong capability was not rejected.");
        Arrays.fill (wrongCapability, (byte) 0);

        try (Socket active = connect ())
        {
            authenticate (active, firstCapability, 5, 6);
            final long start = System.nanoTime ();
            first.beginShutdown ();
            first.awaitShutdown ();
            final long elapsedMillis = (System.nanoTime () - start) / 1_000_000L;
            require (elapsedMillis <= ExternalRasterReceiver.RECEIVER_JOIN_MILLIS + 500, "Active shutdown exceeded its bound: " + elapsedMillis + " ms.");
        }
        require (first.getState () == PushwigExternalIngressActivation.State.CLOSED, "First activation did not close.");
        require (!Files.exists (first.getRendezvous ().getManifestPath (), LinkOption.NOFOLLOW_LINKS), "Shutdown left a manifest.");
        require (!Files.exists (first.getRendezvous ().getCapabilityPath (), LinkOption.NOFOLLOW_LINKS), "Shutdown left a capability.");
        require (!isListenerReachable (), "Shutdown left a listener.");

        final PushwigExternalIngressActivation second = PushwigExternalIngressActivation.start (hostLog.host ());
        require (second != null, "Immediate restart failed.");
        final Discovery secondDiscovery = discover ();
        final byte [] secondCapability = secondDiscovery.capability ();
        require (!firstGeneration.equals (secondDiscovery.generation ()), "Restart reused the session generation.");
        require (!Arrays.equals (firstCapability, secondCapability), "Restart reused the capability.");

        final long staleBefore = second.getPipeline ().getReceiver ().getAuthenticationRejects ();
        sendHelloAndClose (firstCapability, 7, 8);
        waitFor (() -> second.getPipeline ().getReceiver ().getAuthenticationRejects () > staleBefore, "Previous-session capability was not rejected.");
        try (Socket valid = connect ())
        {
            authenticate (valid, secondCapability, 9, 10);
            sendClear (valid, 9, 10, 1);
        }

        waitFor (() -> second.getPipeline ().getReceiver ().getDisconnects () == 1, "Restarted producer disconnect was not observed.");
        second.getPipeline ().getReceiver ().beginShutdown ();
        second.getPipeline ().getReceiver ().awaitShutdown ();
        waitFor (() -> !Files.exists (second.getRendezvous ().getManifestPath (), LinkOption.NOFOLLOW_LINKS) && !Files.exists (second.getRendezvous ().getCapabilityPath (), LinkOption.NOFOLLOW_LINKS), "Receiver-terminal callback left rendezvous authority.");
        second.awaitShutdown ();
        require (!isListenerReachable (), "Restarted receiver did not shut down.");
        require (capabilityCount (runtimeRoot ()) == 0, "Restart cleanup left a capability.");
        Arrays.fill (firstCapability, (byte) 0);
        Arrays.fill (secondCapability, (byte) 0);
    }


    private static void testDisplayStartupBoundary (final HostLog hostLog) throws Exception
    {
        final String previousDiagnostic = System.getProperty ("pushwig.syntheticOverlay");
        System.setProperty ("pushwig.syntheticOverlay", "true");
        try
        {
            final BitmapProbe bitmap = new BitmapProbe ();
            final Push2Display display = createDisplay (hostLog, bitmap);
            try
            {
                requireNoAuthority ();
                require (pipelineOf (display) == PassThroughPushFramePipeline.INSTANCE, "External request fell through to a diagnostic before startup.");
                display.send ();
                display.startExternalIngress ();
                final Discovery discovery = discover ();
                try
                {
                    require (capabilityCount (runtimeRoot ()) == 1 && isListenerReachable (), "Startup did not create exactly one current capability/listener.");
                    final PushFramePipeline pipeline = pipelineOf (display);
                    require (pipeline instanceof ExternalRasterPushFramePipeline, "Startup did not publish the external pipeline.");
                    display.startExternalIngress ();
                    require (pipelineOf (display) == pipeline && capabilityCount (runtimeRoot ()) == 1, "Repeated startup created another activation.");
                    require (MAPPER.readTree (Files.readAllBytes (runtimeRoot ().resolve ("current.json"))).path ("session_generation").asText ().equals (discovery.generation ()), "Repeated startup changed generation.");
                    try (Socket socket = connect ())
                    {
                        authenticate (socket, discovery.capability (), 11, 12);
                        sendFrame (socket, 11, 12, 1, 7, 9, 1, 1, 4, new byte [] { 1, 2, 3, (byte) 0xFF });
                        waitFor (() -> ((ExternalRasterPushFramePipeline) pipeline).getReceiver ().getAcceptedFrames () == 1, "Display-owned receiver did not accept a frame.");
                        display.send ();
                        final int rendered = bitmap.renders;
                        display.send ();
                        require (bitmap.writes == 2 && bitmap.renders == rendered + 1, "External application did not redraw unchanged current semantics on each send.");
                    }
                }
                finally
                {
                    Arrays.fill (discovery.capability (), (byte) 0);
                }
            }
            finally
            {
                display.shutdown ();
            }
            display.startExternalIngress ();
            requireNoAuthority ();

            final Push2Display neverStarted = createDisplay (hostLog, new BitmapProbe ());
            neverStarted.shutdown ();
            neverStarted.startExternalIngress ();
            requireNoAuthority ();

            final Push2Display failed = createDisplay (hostLog, new BitmapProbe ());
            try
            {
                // A real occupied listener makes the actual production activation fail.
                try (ServerSocket collision = new ServerSocket ())
                {
                    collision.bind (new InetSocketAddress (InetAddress.getByName ("127.0.0.1"), PORT));
                    failed.startExternalIngress ();
                    require (pipelineOf (failed) == PassThroughPushFramePipeline.INSTANCE, "Failure selected a lower diagnostic pipeline.");
                    require (capabilityCount (runtimeRoot ()) == 0 && !Files.exists (runtimeRoot ().resolve ("current.json")), "Failure leaked authority.");
                }
                failed.startExternalIngress ();
                requireNoAuthority ();
                failed.send ();
            }
            finally
            {
                failed.shutdown ();
            }
            System.out.println ("Display startup boundary: pre-startup / once / repeat / shutdown-first / failure / redraw PASS");
        }
        finally
        {
            if (previousDiagnostic == null)
                System.clearProperty ("pushwig.syntheticOverlay");
            else
                System.setProperty ("pushwig.syntheticOverlay", previousDiagnostic);
        }
    }


    private static void testSamplerContextLifecycle (final HostLog hostLog) throws Exception
    {
        final Path notices = runtimeRoot ().resolveSibling ("sampler-lens-v1");
        Path current = null;
        final BitmapProbe bitmap = new BitmapProbe ();
        final Push2Display display = createDisplay (hostLog, bitmap);
        final List<SamplerLensPresentation.Slot> slots = new ArrayList<> ();
        for (int i = 0; i < 8; i++)
            slots.add (new SamplerLensPresentation.Slot ("Action", true, "Device", true,
                de.mossgrabers.framework.controller.color.ColorEx.ORANGE, "Remote", "100 %", true, false));
        final SamplerLensPresentation screen = new SamplerLensPresentation (slots);
        try
        {
            display.manageSamplerLens ();
            display.acquireSamplerContext ();
            require (!Files.exists (notices), "Construction/acquisition before startup published context authority.");
            display.startExternalIngress ();
            current = notices.resolve (discover ().generation () + ".json");
            final Path notice = current;
            waitFor (() -> Files.exists (notice), "Context notice was not published.");
            final JsonNode first = MAPPER.readTree (Files.readString (current));
            require (first.get ("context_session").asText ().matches ("[0-9a-f]{32}"), "Missing context session.");
            require (first.get ("ingress_generation").asText ().equals (discover ().generation ()), "Context is not associated with the current ingress lifecycle.");
            require (Files.getPosixFilePermissions (current).equals (java.nio.file.attribute.PosixFilePermissions.fromString ("rw-------")), "Context notice is not private.");
            try (final var files = Files.list (runtimeRoot ()))
            {
                require (files.noneMatch (p -> p.getFileName ().toString ().contains ("sampler")), "Sampler added an unknown file inside the protected V5A directory.");
            }
            final String firstSession = first.get ("context_session").asText ();
            display.acquireSamplerContext ();
            require (firstSession.equals (MAPPER.readTree (Files.readString (current)).get ("context_session").asText ()), "Repeated acquisition changed current identity.");

            final long high = Long.parseUnsignedLong (firstSession.substring (0, 16), 16);
            final long low = Long.parseUnsignedLong (firstSession.substring (16), 16);
            final Discovery discovery = discover ();
            final ExternalRasterPushFramePipeline external = (ExternalRasterPushFramePipeline) pipelineOf (display);
            try (Socket socket = connect ())
            {
                authenticate (socket, discovery.capability (), high, low);
                final byte [] center = new byte [484 * 114 * 4];
                for (int i = 3; i < center.length; i += 4) center[i] = (byte) 255;
                sendFrame (socket, high, low, 1, 238, 25, 484, 114, 484 * 4, center);
                waitFor (() -> external.getReceiver ().getAcceptedFrames () == 1, "Sampler frame not received.");
                display.prepareSamplerPresentation (screen);
                display.send ();
                require (bitmap.writes == 1, "Real display did not apply eligible image after current semantic redraw.");
                display.send ();
                require (bitmap.writes == 1, "A send without a fresh Device-mode presentation reused old contextual state.");

                sendFrame (socket, high, low, 2, 238, 25, 484, 114, 484 * 4, center);
                waitFor (() -> external.getReceiver ().getAcceptedFrames () == 2, "Second Sampler frame not received.");
                display.prepareSamplerPresentation (screen);
                display.notifyPlayedChord ("C#3");
                display.send ();
                require (bitmap.writes == 2, "Typed played-note feedback evicted the current Sampler image.");
                sendFrame (socket, high, low, 3, 238, 25, 484, 114, 484 * 4, center);
                waitFor (() -> external.getReceiver ().getAcceptedFrames () == 3, "Third Sampler frame not received.");
                display.prepareSamplerPresentation (screen);
                // Same text through the ordinary path MUST still block: no note-name string matching.
                display.notify ("C#3");
                display.notifyPlayedChord ("D#3");
                display.send ();
                require (bitmap.writes == 2, "Played feedback erased or bypassed an ordinary blocking notification.");
                display.setNotificationMessage (null);
                sendFrame (socket, high, low, 4, 238, 25, 484, 114, 484 * 4, center);
                waitFor (() -> external.getReceiver ().getAcceptedFrames () == 4, "Fourth Sampler frame not received.");
                display.prepareSamplerPresentation (screen);
                display.addGraphOverlay (0, 0, 100, 50, de.mossgrabers.framework.controller.color.ColorEx.ORANGE, new int [] { 0, 1 }, 1);
                display.notifyPlayedChord ("E3");
                display.send ();
                require (bitmap.writes == 2, "Typed played feedback bypassed a real semantic overlay.");
            }
            finally { Arrays.fill (discovery.capability (), (byte) 0); }

            waitFor (() -> external.getReceiver ().getDisconnects () > 0, "Producer disconnect was not observed.");
            display.acquireSamplerContext ();
            waitFor (() -> !firstSession.equals (MAPPER.readTree (Files.readString (notice)).get ("context_session").asText ()), "Reconnect would reuse a session across connections.");
            final String reconnectSession = MAPPER.readTree (Files.readString (notice)).get ("context_session").asText ();
            final Discovery reconnect = discover ();
            String newerSession;
            try (Socket socket = connect ())
            {
                authenticate (socket, reconnect.capability (), Long.parseUnsignedLong (reconnectSession.substring (0, 16), 16), Long.parseUnsignedLong (reconnectSession.substring (16), 16));
                waitFor (() -> external.getReceiver ().getAcceptedSessions () == 2, "Reconnect HELLO was not accepted.");
                display.revokeSamplerContext ();
                display.acquireSamplerContext ();
                waitFor (() -> !reconnectSession.equals (MAPPER.readTree (Files.readString (notice)).get ("context_session").asText ()), "New context not published while old connection remained open.");
                newerSession = MAPPER.readTree (Files.readString (notice)).get ("context_session").asText ();
            }
            finally { Arrays.fill (reconnect.capability (), (byte) 0); }
            waitFor (() -> external.getReceiver ().getDisconnects () == 2, "Old connection did not end.");
            display.acquireSamplerContext ();
            require (newerSession.equals (MAPPER.readTree (Files.readString (notice)).get ("context_session").asText ()), "Closing an old context churned the newer context identity.");
            display.revokeSamplerContext ();
            require (!display.isSamplerPresentationRequested (), "Local context loss waited for filesystem publication.");
            display.notifyPlayedChord ("F3");
            display.send ();
            final var modelField = de.mossgrabers.framework.controller.display.AbstractGraphicDisplay.class.getDeclaredField ("info");
            modelField.setAccessible (true);
            final var renderedModel = (de.mossgrabers.framework.graphics.display.ModelInfo) modelField.get (display);
            require ("F3".equals (renderedModel.getNotification ()), "Note feedback outside Sampler lost its ordinary notification behavior.");
            display.setNotificationMessage (null);
            waitFor (() -> MAPPER.readTree (Files.readString (notice)).get ("context_session").isNull (), "Inactive context notice did not follow local revocation.");
            display.acquireSamplerContext ();
            waitFor (() -> !MAPPER.readTree (Files.readString (notice)).get ("context_session").isNull (), "New context did not publish.");
            require (!firstSession.equals (MAPPER.readTree (Files.readString (current)).get ("context_session").asText ()), "Re-entry reused the previous context session.");
        }
        finally { display.shutdown (); }
        display.acquireSamplerContext ();
        require (!display.isSamplerPresentationRequested (), "Shutdown allowed context reacquisition.");
        try (final var files = Files.list (notices))
        {
            require (files.findAny ().isEmpty (), "Context notices remain after shutdown.");
        }
        requireNoAuthority ();
        System.out.println ("Sampler display lifecycle: pre-startup / private notice / context change / fresh presentation / notification / shutdown PASS");
    }


    private static void requireNoAuthority () throws Exception
    {
        require (!Files.exists (runtimeRoot ().resolve ("current.json")), "Unexpected current manifest.");
        require (capabilityCount (runtimeRoot ()) == 0, "Unexpected capability.");
        require (!isListenerReachable (), "Unexpected listener.");
    }


    private static PushFramePipeline pipelineOf (final Push2Display display) throws Exception
    {
        final var field = Push2Display.class.getDeclaredField ("framePipeline");
        field.setAccessible (true);
        return (PushFramePipeline) field.get (display);
    }


    private static Push2Display createDisplay (final HostLog hostLog, final BitmapProbe bitmap)
    {
        final IHost originalHost = hostLog.host ();
        final IHost host = (IHost) Proxy.newProxyInstance (IHost.class.getClassLoader (), new Class [] { IHost.class }, (proxy, method, arguments) -> {

            if ("createBitmap".equals (method.getName ()))
                return bitmap.bitmap ();
            if ("createMemoryBlock".equals (method.getName ()))
            {
                final ByteBuffer bytes = ByteBuffer.allocate ((int) arguments[0]);
                return (IMemoryBlock) () -> bytes;
            }
            if ("getUsbDevice".equals (method.getName ()))
                throw new UsbException ("No physical USB in deterministic display test.");
            return method.invoke (originalHost, arguments);

        });
        final PushConfiguration configuration = new PushConfiguration (host, null, List.of (), PushVersion.VERSION_3)
        {
            @Override
            public boolean isPushwigExternalRasterIngressEnabled ()
            {
                return true;
            }
        };
        return new Push2Display (host, 128, configuration);
    }


    private static final class BitmapProbe
    {
        private int renders;
        private int writes;
        private int renderAtLastWrite;

        private IRasterWritableBitmap bitmap ()
        {
            // Host-adapter observation only: real display, pipeline, receiver and store run above.
            return (IRasterWritableBitmap) Proxy.newProxyInstance (IRasterWritableBitmap.class.getClassLoader (), new Class [] { IRasterWritableBitmap.class }, (proxy, method, arguments) -> {

                if ("render".equals (method.getName ()))
                    this.renders++;
                if ("writeRasterRegion".equals (method.getName ()))
                {
                    require (this.renders > this.renderAtLastWrite, "External frame applied without a fresh semantic redraw.");
                    this.renderAtLastWrite = this.renders;
                    this.writes++;
                    return Boolean.TRUE;
                }
                return null;

            });
        }
    }


    private static Socket connect () throws IOException
    {
        final Socket socket = new Socket ();
        socket.connect (new InetSocketAddress (InetAddress.getByAddress (new byte []
        {
            127,
            0,
            0,
            1
        }), PORT), 500);
        socket.setSoTimeout (1000);
        return socket;
    }


    private static boolean isListenerReachable ()
    {
        try (Socket ignored = connect ())
        {
            return true;
        }
        catch (final IOException ex)
        {
            return false;
        }
    }


    private static void authenticate (final Socket socket, final byte [] capability, final long sessionHigh, final long sessionLow) throws IOException
    {
        final OutputStream output = socket.getOutputStream ();
        output.write (header (ExternalRasterReceiver.MESSAGE_HELLO, ExternalRasterReceiver.FORMAT_NONE, sessionHigh, sessionLow, 0, 0, 0, 0, 0, 0, ExternalRasterReceiver.TOKEN_BYTES));
        output.write (capability);
        output.flush ();
    }


    private static void sendHelloAndClose (final byte [] capability, final long sessionHigh, final long sessionLow) throws IOException
    {
        try (Socket socket = connect ())
        {
            authenticate (socket, capability, sessionHigh, sessionLow);
        }
    }


    private static void sendFrame (final Socket socket, final long sessionHigh, final long sessionLow, final long sequence, final int destinationX, final int destinationY, final int width, final int height, final int stride, final byte [] payload) throws IOException
    {
        final OutputStream output = socket.getOutputStream ();
        output.write (header (ExternalRasterReceiver.MESSAGE_FRAME, ExternalRasterReceiver.FORMAT_OPAQUE_BGRA, sessionHigh, sessionLow, sequence, destinationX, destinationY, width, height, stride, payload.length));
        output.write (payload);
        output.flush ();
    }


    private static void sendClear (final Socket socket, final long sessionHigh, final long sessionLow, final long sequence) throws IOException
    {
        final OutputStream output = socket.getOutputStream ();
        output.write (header (ExternalRasterReceiver.MESSAGE_CLEAR, ExternalRasterReceiver.FORMAT_NONE, sessionHigh, sessionLow, sequence, 0, 0, 0, 0, 0, 0));
        output.flush ();
    }


    private static byte [] header (final int messageType, final int format, final long sessionHigh, final long sessionLow, final long sequence, final int destinationX, final int destinationY, final int width, final int height, final int stride, final int payloadLength)
    {
        final ByteBuffer header = ByteBuffer.allocate (ExternalRasterReceiver.HEADER_LENGTH).order (ByteOrder.BIG_ENDIAN);
        header.putInt (ExternalRasterReceiver.MAGIC);
        header.putShort ((short) ExternalRasterReceiver.VERSION);
        header.putShort ((short) ExternalRasterReceiver.HEADER_LENGTH);
        header.putInt (messageType);
        header.putInt (0);
        header.putInt (format);
        header.putInt (0);
        header.putLong (sessionHigh);
        header.putLong (sessionLow);
        header.putLong (sequence);
        header.putInt (destinationX);
        header.putInt (destinationY);
        header.putInt (width);
        header.putInt (height);
        header.putInt (stride);
        header.putInt (payloadLength);
        header.putLong (0);
        return header.array ();
    }


    private static byte [] readCapability (final Path path) throws IOException
    {
        final byte [] hexadecimal = Files.readAllBytes (path);
        try
        {
            require (hexadecimal.length == 64, "Capability text length changed.");
            final byte [] result = new byte [32];
            for (int index = 0; index < result.length; index++)
            {
                final int high = Character.digit ((char) hexadecimal[index * 2], 16);
                final int low = Character.digit ((char) hexadecimal[index * 2 + 1], 16);
                require (high >= 0 && low >= 0, "Capability text is not hexadecimal.");
                result[index] = (byte) (high << 4 | low);
            }
            return result;
        }
        finally
        {
            Arrays.fill (hexadecimal, (byte) 0);
        }
    }


    private static Discovery discover () throws IOException
    {
        final Path root = runtimeRoot ();
        final Path manifestPath = root.resolve ("current.json");
        final JsonNode manifest = MAPPER.readTree (Files.readString (manifestPath, StandardCharsets.UTF_8));
        require (manifest.path ("schema_version").asInt () == PushwigRuntimeRendezvous.SCHEMA_VERSION, "Producer rejected the manifest schema.");
        require (manifest.path ("protocol_version").asInt () == PushwigRuntimeRendezvous.PROTOCOL_VERSION, "Producer rejected the protocol version.");
        require ("ipv4-loopback".equals (manifest.path ("transport").asText ()), "Producer rejected the transport.");
        require (manifest.path ("owner_pid").asLong () == ProcessHandle.current ().pid (), "Producer rejected owner liveness.");
        final String generation = manifest.path ("session_generation").asText ();
        final String capabilityName = manifest.path ("capability_file").asText ();
        require (capabilityName.equals ("capability-" + generation + ".hex"), "Producer rejected the capability basename.");
        final Path capabilityPath = root.resolve (capabilityName).normalize ();
        require (capabilityPath.getParent ().equals (root), "Capability escaped the runtime root.");
        return new Discovery (manifest.path ("port").asInt (), generation, readCapability (capabilityPath));
    }


    private static long capabilityCount (final Path root) throws IOException
    {
        if (!Files.isDirectory (root, LinkOption.NOFOLLOW_LINKS))
            return 0;
        try (var entries = Files.list (root))
        {
            return entries.filter (path -> path.getFileName ().toString ().startsWith ("capability-")).count ();
        }
    }


    private static Path runtimeRoot ()
    {
        return Path.of (System.getProperty ("user.home"), ".pushwig", "runtime", "external-raster-v1");
    }


    private static void waitFor (final CheckedCondition condition, final String failure) throws Exception
    {
        final long deadline = System.nanoTime () + 2_000_000_000L;
        while (System.nanoTime () < deadline)
        {
            if (condition.test ())
                return;
            Thread.sleep (2);
        }
        throw new AssertionError (failure);
    }


    private static void deleteTree (final Path root) throws IOException
    {
        if (!Files.exists (root, LinkOption.NOFOLLOW_LINKS))
            return;
        try (var paths = Files.walk (root))
        {
            for (final Path path: paths.sorted (Comparator.reverseOrder ()).toList ())
                Files.deleteIfExists (path);
        }
    }


    private static void require (final boolean condition, final String message)
    {
        if (!condition)
            throw new AssertionError (message);
    }


    @FunctionalInterface
    private interface CheckedCondition
    {
        boolean test () throws Exception;
    }


    private record Discovery (int port, String generation, byte [] capability)
    {
        // Immutable producer discovery facts; the caller zeroes the capability after use.
    }


    private static final class HostLog
    {
        private final List<String> messages = Collections.synchronizedList (new ArrayList<> ());


        private IHost host ()
        {
            return (IHost) Proxy.newProxyInstance (IHost.class.getClassLoader (), new Class []
            {
                IHost.class
            }, (proxy, method, arguments) -> {

                if (("println".equals (method.getName ()) || "error".equals (method.getName ())) && arguments != null && arguments.length > 0)
                    this.messages.add (String.valueOf (arguments[0]));
                if (method.getReturnType () == boolean.class)
                    return Boolean.FALSE;
                if (method.getReturnType () == int.class)
                    return Integer.valueOf (0);
                if (method.getReturnType () == long.class)
                    return Long.valueOf (0);
                if (method.getReturnType () == double.class)
                    return Double.valueOf (0);
                if (method.getReturnType () == int [].class)
                    return new int []
                    {
                        0,
                        0
                    };
                if ("getName".equals (method.getName ()))
                    return "Test Host";
                return null;

            });
        }
    }
}
