// Pushwig contextual composition proof (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.controller.color.ColorEx;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.graphics.IBitmap;
import de.mossgrabers.framework.graphics.IGraphicsContext;
import de.mossgrabers.framework.graphics.IRasterWritableBitmap;
import de.mossgrabers.framework.graphics.IRenderer;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;


/** Runs the real receiver/store/pipeline, using generated semantic pixels and a recording bitmap. */
public final class SamplerLensCompositionTest
{
    private static int checks;
    private static final int PORT = 45293;
    private static final byte [] CAPABILITY = new byte [32];


    public static void main (final String [] args) throws Exception
    {
        final Path root = Files.createTempDirectory ("pushwig-sampler-composition-");
        final Path token = root.resolve ("capability");
        ExternalRasterPushFramePipeline pipeline = null;
        try
        {
            // Generated test secret only. Never reads or changes the installed runtime authority.
            Arrays.fill (CAPABILITY, (byte) 0xA5);
            Files.writeString (token, HexFormat.of ().formatHex (CAPABILITY));
            Files.setPosixFilePermissions (token, PosixFilePermissions.fromString ("rw-------"));
            final IHost host = (IHost) Proxy.newProxyInstance (IHost.class.getClassLoader (), new Class<?> [] { IHost.class }, (p, m, a) -> null);
            pipeline = ExternalRasterPushFramePipeline.create (host, PORT, token.toString (), 100, () -> { });
            check (pipeline != null, "Test receiver starts");
            testPresentation ();
            testLiveProtocol (pipeline);
            if (args.length == 1)
                testSwiftProducer (pipeline, args[0]);
            System.out.println ("SamplerLensCompositionTest: PASS (" + checks + " checks; generated frames only)");
        }
        finally
        {
            if (pipeline != null)
            {
                pipeline.beginShutdown ();
                pipeline.awaitShutdown ();
            }
            Arrays.fill (CAPABILITY, (byte) 0);
            Files.deleteIfExists (token);
            Files.delete (root);
        }
    }


    private static SamplerLensPresentation presentation (final int touchedMask)
    {
        final String [] actions = { "On", "Parameters", "Expanded", "Chains", "Banks", "Pin Device", "Window", "Up" };
        final String [] aliases = { "Speed", "Pitch", "Start", "Glide time", "Pan", "Vel Sens.", "Gain", "Output" };
        final List<SamplerLensPresentation.Slot> slots = new ArrayList<> (8);
        for (int i = 0; i < 8; i++)
            slots.add (new SamplerLensPresentation.Slot (actions[i], i % 2 == 0, "Page " + (i + 1), i == 2, ColorEx.ORANGE, aliases[i], i + ".25 unit", true, (touchedMask & (1 << i)) != 0));
        return new SamplerLensPresentation (slots);
    }


    private static void testPresentation ()
    {
        final Bitmap bitmap = new Bitmap ();
        final int [] before = bitmap.pixels.clone ();
        presentation (0).render (bitmap.context);
        check (bitmap.centerMismatches (before) == 0, "Painter preserves every center image pixel");
        for (int slot = 0; slot < 8; slot++)
        {
            final String value = slot + ".25 unit";
            final int expectedX = slot < 4 ? 31 : 761;
            final int expectedY = 36 + slot % 4 * 28;
            check (bitmap.texts.stream ().anyMatch (t -> t.text.equals (value) && t.x == expectedX && t.y == expectedY), "Each formatted value is permanently visible in its physical-side readout");
            final int column = slot;
            check (bitmap.texts.stream ().anyMatch (t -> t.text.equals ("Page " + (column + 1)) && t.x == column * 120 + 5 && t.y == 142), "Navigation remains in physical column");
        }
        final int [] untouched = bitmap.pixels.clone ();
        bitmap.reset ();
        presentation (0b10000001).render (bitmap.context);
        check (bitmap.centerMismatches (untouched) == 0, "Multiple touches never move or repaint image pixels");
        check (bitmap.texts.stream ().filter (t -> t.text.endsWith (".25 unit")).count () == 8, "Touch does not hide other values");
        check (bitmap.texts.stream ().anyMatch (t -> t.text.equals ("On") && t.x == 3), "Top action label is not replaced by remote alias");
        check (bitmap.texts.stream ().anyMatch (t -> t.text.equals ("Window") && t.x == 723), "Device Window action retains its position");
        boolean rejected = false;
        try { new SamplerLensPresentation (List.of ()); }
        catch (final IllegalArgumentException expected) { rejected = true; }
        check (rejected, "Incomplete physical-slot state refuses");
    }


    private static void testLiveProtocol (final ExternalRasterPushFramePipeline pipeline) throws Exception
    {
        final byte [] image = new byte [SamplerLensPresentation.IMAGE_WIDTH * SamplerLensPresentation.IMAGE_HEIGHT * 4];
        for (int i = 0; i < image.length; i += 4)
        {
            image[i] = (byte) (i % 251);
            image[i + 1] = 71;
            image[i + 2] = (byte) 197;
            image[i + 3] = (byte) 255;
        }
        final Bitmap bitmap = new Bitmap ();
        final SamplerLensPresentation screen = presentation (0);
        final int [] semantic = bitmap.pixels.clone ();
        check (pipeline.processSampler (bitmap.object, 101, 201, screen) == bitmap.object, "Missing frame returns same semantic bitmap");
        check (Arrays.equals (semantic, bitmap.pixels), "Missing frame leaves full current semantics");

        try (Socket client = connect (101, 201))
        {
            frame (client, 101, 201, 1, 238, 25, image);
            waitFor (() -> pipeline.getReceiver ().getAcceptedFrames () == 1);
            final LatestExternalRasterFrameStore.DisplayFrame adopted = new LatestExternalRasterFrameStore.DisplayFrame ();
            check (pipeline.getStore ().tryAdopt (adopted, System.nanoTime ()), "Actual receiver publication is adoptable");
            check (adopted.sessionHigh == 101 && adopted.sessionLow == 201, "Both authenticated session halves survive receiver/store adoption");
            final byte [] payloadHash = MessageDigest.getInstance ("SHA-256").digest (image);
            check (MessageDigest.isEqual (payloadHash, MessageDigest.getInstance ("SHA-256").digest (Arrays.copyOf (adopted.bytes, image.length))), "Retaining identity does not change payload");
            check (pipeline.processSampler (bitmap.object, 101, 201, screen) == bitmap.object, "Eligible composition preserves bitmap identity");
            check (bitmap.writes == 1 && bitmap.renders == 1, "One raster write plus one synchronous semantic margin render");
            check (bitmap.payloadMismatches (image) == 0, "Center retains exact accepted image after semantic drawing");

            // A current context mismatch must refuse a still-fresh frame, not rely on producer CLEAR.
            bitmap.reset ();
            pipeline.processSampler (bitmap.object, 102, 202, screen);
            check (Arrays.equals (semantic, bitmap.pixels) && bitmap.writes == 0, "Old-context frame cannot overwrite new-context semantics");
            pipeline.processSampler (bitmap.object, 101, 201, screen);
            check (Arrays.equals (semantic, bitmap.pixels), "Previously refused publication cannot resurrect on context return");

            frame (client, 101, 201, 2, 238, 25, image);
            waitFor (() -> pipeline.getReceiver ().getAcceptedFrames () == 2);
            pipeline.processSampler (bitmap.object, 101, 201, null);
            check (Arrays.equals (semantic, bitmap.pixels), "Absent current presentation remains semantic-only, never generic ingress");

            frame (client, 101, 201, 3, 237, 25, image);
            waitFor (() -> pipeline.getReceiver ().getAcceptedFrames () == 3);
            pipeline.processSampler (bitmap.object, 101, 201, screen);
            check (Arrays.equals (semantic, bitmap.pixels), "Payload targeting a readout column refuses before any pixel write");

            frame (client, 101, 201, 4, 238, 25, image);
            waitFor (() -> pipeline.getReceiver ().getAcceptedFrames () == 4);
            bitmap.acceptWrite = false;
            pipeline.processSampler (bitmap.object, 101, 201, screen);
            check (Arrays.equals (semantic, bitmap.pixels) && bitmap.renders == 0, "Failed raster write does not erase semantics or paint compact margins");
            bitmap.acceptWrite = true;
        }
        waitFor (() -> pipeline.getReceiver ().getDisconnects () == 1);
        bitmap.reset ();
        pipeline.processSampler (bitmap.object, 101, 201, screen);
        check (Arrays.equals (semantic, bitmap.pixels), "Disconnect restores current semantic-only path");

        try (Socket client = connect (102, 202))
        {
            frame (client, 102, 202, 1, 238, 25, image);
            waitFor (() -> pipeline.getReceiver ().getAcceptedFrames () == 5);
            pipeline.processSampler (bitmap.object, 102, 202, screen);
            check (bitmap.payloadMismatches (image) == 0, "New context/connection accepts new pixels");
            client.getOutputStream ().write (header (3, 102, 202, 2, 0, 0, 0, 0, 0, 0));
            waitFor (() -> pipeline.getStore ().getClearOperations () == 1);
            bitmap.reset ();
            pipeline.processSampler (bitmap.object, 102, 202, screen);
            check (Arrays.equals (semantic, bitmap.pixels), "CLEAR preserves complete current semantics");

            frame (client, 102, 202, 3, 238, 25, image);
            waitFor (() -> pipeline.getReceiver ().getAcceptedFrames () == 6);
            Thread.sleep (120);
            pipeline.processSampler (bitmap.object, 102, 202, screen);
            check (Arrays.equals (semantic, bitmap.pixels), "Stale frame preserves complete current semantics");

            // The accepted general-purpose route still uses its ordinary destination contract.
            frame (client, 102, 202, 4, 7, 9, image);
            waitFor (() -> pipeline.getReceiver ().getAcceptedFrames () == 7);
            check (pipeline.process (bitmap.object) == bitmap.object && bitmap.writes == 1 && bitmap.renders == 0, "Generic ingress remains unchanged without contextual composition");
        }
    }


    private static void testSwiftProducer (final ExternalRasterPushFramePipeline pipeline, final String testExecutable) throws Exception
    {
        final long accepted = pipeline.getReceiver ().getAcceptedFrames ();
        final long clears = pipeline.getStore ().getClearOperations ();
        final Process producer = new ProcessBuilder (testExecutable, "--interop", Integer.toString (PORT)).redirectError (ProcessBuilder.Redirect.INHERIT).start ();
        try
        {
            final var messages = producer.inputReader ();
            final var commands = producer.outputWriter ();
            final long high = 0x1111111111111111L, low = 0x2222222222222222L;
            waitFor (() -> pipeline.getReceiver ().getAcceptedFrames () == accepted + 1);
            check ("FRAME".equals (messages.readLine ()), "Actual Swift producer sent FRAME");
            final Bitmap bitmap = new Bitmap ();
            pipeline.processSampler (bitmap.object, high, low, presentation (0));
            final byte [] expected = new byte [484 * 114 * 4];
            Arrays.fill (expected, (byte) 255);
            check (bitmap.writes == 1 && bitmap.payloadMismatches (expected) == 0, "Swift producer -> real Java receiver -> contextual raster exact pixels");
            commands.write ("CLEAR\n"); commands.flush ();
            waitFor (() -> pipeline.getStore ().getClearOperations () == clears + 1);
            check ("CLEAR".equals (messages.readLine ()), "Actual Swift producer sent CLEAR");
            bitmap.reset ();
            final int [] semantic = bitmap.pixels.clone ();
            pipeline.processSampler (bitmap.object, high, low, presentation (0));
            check (bitmap.writes == 0 && Arrays.equals (semantic, bitmap.pixels), "Swift CLEAR restores full current semantic bitmap");
            commands.write ("EXIT\n"); commands.flush ();
            check (producer.waitFor (2, TimeUnit.SECONDS) && producer.exitValue () == 0, "Swift generated producer exits boundedly");
            System.out.println ("Actual Swift producer -> Java receiver/pipeline: FRAME/CLEAR exact pixels PASS (generated endpoints)");
        }
        finally
        {
            if (producer.isAlive ())
            {
                producer.destroy ();
                if (!producer.waitFor (1, TimeUnit.SECONDS)) producer.destroyForcibly ();
            }
        }
    }


    private static Socket connect (final long high, final long low) throws Exception
    {
        final Socket socket = new Socket (InetAddress.getByAddress (new byte [] { 127, 0, 0, 1 }), PORT);
        socket.getOutputStream ().write (header (1, high, low, 0, 0, 0, 0, 0, 0, 32));
        socket.getOutputStream ().write (CAPABILITY);
        return socket;
    }


    private static void frame (final Socket socket, final long high, final long low, final long sequence, final int x, final int y, final byte [] image) throws Exception
    {
        socket.getOutputStream ().write (header (2, high, low, sequence, x, y, 484, 114, 484 * 4, image.length));
        socket.getOutputStream ().write (image);
    }


    private static byte [] header (final int type, final long high, final long low, final long sequence, final int x, final int y, final int w, final int h, final int stride, final int length)
    {
        final ByteBuffer header = ByteBuffer.allocate (80).order (ByteOrder.BIG_ENDIAN);
        header.putInt (0, 0x50575852);
        header.putShort (4, (short) 1);
        header.putShort (6, (short) 80);
        header.putInt (8, type);
        header.putInt (16, type == 2 ? 1 : 0);
        header.putLong (24, high);
        header.putLong (32, low);
        header.putLong (40, sequence);
        header.putInt (48, x);
        header.putInt (52, y);
        header.putInt (56, w);
        header.putInt (60, h);
        header.putInt (64, stride);
        header.putInt (68, length);
        return header.array ();
    }


    private static void waitFor (final BooleanSupplier condition) throws Exception
    {
        final long until = System.nanoTime () + TimeUnit.SECONDS.toNanos (2);
        while (!condition.getAsBoolean () && System.nanoTime () < until)
            Thread.sleep (1);
        check (condition.getAsBoolean (), "Receiver progressed within bound");
    }


    private static void check (final boolean condition, final String message)
    {
        checks++;
        if (!condition) throw new AssertionError (message);
    }


    private record Text (String text, double x, double y) { }

    private static final class Bitmap
    {
        private final int [] pixels = new int [960 * 160];
        private final List<Text> texts = new ArrayList<> ();
        private int writes;
        private int renders;
        private boolean acceptWrite = true;
        private final IGraphicsContext context;
        private final IRasterWritableBitmap object;

        private Bitmap ()
        {
            this.context = (IGraphicsContext) Proxy.newProxyInstance (IGraphicsContext.class.getClassLoader (), new Class<?> [] { IGraphicsContext.class }, (p, m, a) -> {
                if (m.getName ().equals ("fillRectangle"))
                {
                    final int x = ((Number) a[0]).intValue (), y = ((Number) a[1]).intValue ();
                    final int w = ((Number) a[2]).intValue (), h = ((Number) a[3]).intValue ();
                    check (x >= 0 && y >= 0 && x + w <= 960 && y + h <= 160, "Every drawing operation is bounded");
                    final int color = a[4].hashCode ();
                    for (int row = y; row < y + h; row++)
                        Arrays.fill (this.pixels, row * 960 + x, row * 960 + x + w, color);
                }
                else if (m.getName ().equals ("drawTextInBounds"))
                {
                    this.texts.add (new Text ((String) a[0], ((Number) a[1]).doubleValue (), ((Number) a[2]).doubleValue ()));
                    final double x = ((Number) a[1]).doubleValue (), y = ((Number) a[2]).doubleValue ();
                    final double w = ((Number) a[3]).doubleValue (), h = ((Number) a[4]).doubleValue ();
                    check (x >= 0 && y >= 0 && x + w <= 960 && y + h <= 160, "Text is clipped within screen bounds");
                    check (x + w <= 238 || x >= 722 || y + h <= 25 || y >= 139, "Text never crosses center payload");
                }
                else throw new AssertionError ("Unexpected renderer operation: " + m.getName ());
                return null;
            });
            this.object = (IRasterWritableBitmap) Proxy.newProxyInstance (IRasterWritableBitmap.class.getClassLoader (), new Class<?> [] { IRasterWritableBitmap.class }, (p, m, a) -> {
                if (m.getName ().equals ("writeRasterRegion"))
                {
                    if (!this.acceptWrite) return false;
                    this.writes++;
                    final byte [] bytes = (byte []) a[1];
                    final int offset = (int) a[2], stride = (int) a[3], x = (int) a[4], y = (int) a[5], w = (int) a[6], h = (int) a[7];
                    for (int row = 0; row < h; row++)
                        for (int col = 0; col < w; col++)
                            this.pixels[(y + row) * 960 + x + col] = pixel (bytes, offset + row * stride + col * 4);
                    return true;
                }
                if (m.getName ().equals ("render"))
                {
                    this.renders++;
                    ((IRenderer) a[1]).render (this.context);
                    return null;
                }
                throw new AssertionError ("Unexpected bitmap operation: " + m.getName ());
            });
            this.reset ();
        }

        private void reset ()
        {
            for (int i = 0; i < this.pixels.length; i++) this.pixels[i] = 0xFF000000 | i;
            this.writes = this.renders = 0;
            this.texts.clear ();
        }

        private int centerMismatches (final int [] reference)
        {
            int mismatches = 0;
            for (int y = 25; y < 139; y++)
                for (int x = 238; x < 722; x++)
                    if (this.pixels[y * 960 + x] != reference[y * 960 + x]) mismatches++;
            return mismatches;
        }

        private int payloadMismatches (final byte [] expected)
        {
            int mismatches = 0;
            for (int y = 0; y < 114; y++)
                for (int x = 0; x < 484; x++)
                    if (this.pixels[(y + 25) * 960 + x + 238] != pixel (expected, (y * 484 + x) * 4)) mismatches++;
            return mismatches;
        }

        private static int pixel (final byte [] bytes, final int offset)
        {
            return ByteBuffer.wrap (bytes, offset, 4).order (ByteOrder.LITTLE_ENDIAN).getInt ();
        }
    }
}
