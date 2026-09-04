// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V5A ordinary external-ingress activation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;


/** Deterministic filesystem and authority tests for the V5A rendezvous. */
public final class PushwigRuntimeRendezvousTest
{
    private static final ObjectMapper             MAPPER            = new ObjectMapper ();
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = PosixFilePermissions.fromString ("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE      = PosixFilePermissions.fromString ("rw-------");


    private PushwigRuntimeRendezvousTest ()
    {
        // Utility class.
    }


    public static void main (final String [] arguments) throws Exception
    {
        final String originalHome = System.getProperty ("user.home");
        final Path testHome = Files.createTempDirectory ("pushwig-v5a-rendezvous-");
        try
        {
            System.setProperty ("user.home", testHome.toString ());
            testCreatePublishAndCleanup ();
            testSingleOwnerLock ();
            testSafeStaleCleanup ();
            testUnsafeEntryRefusal ();
            testManifestPublicationCollisionCleanup ();
            System.out.println ("PushwigRuntimeRendezvousTest: PASS");
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


    private static void testCreatePublishAndCleanup () throws Exception
    {
        final PushwigRuntimeRendezvous rendezvous = PushwigRuntimeRendezvous.prepare ();
        final Path capability = rendezvous.getCapabilityPath ();
        require (Files.size (capability) == 64, "Capability is not exactly 64 hexadecimal bytes.");
        require (PRIVATE_FILE.equals (Files.getPosixFilePermissions (capability, LinkOption.NOFOLLOW_LINKS)), "Capability mode is not 0600.");
        require (PRIVATE_DIRECTORY.equals (Files.getPosixFilePermissions (rendezvous.getRuntimeRoot (), LinkOption.NOFOLLOW_LINKS)), "Runtime-root mode is not 0700.");

        rendezvous.publish (PushwigRuntimeRendezvous.DEFAULT_PORT);
        final byte [] capabilityValue = Files.readAllBytes (capability);
        final byte [] manifestBytes = Files.readAllBytes (rendezvous.getManifestPath ());
        final JsonNode manifest = MAPPER.readTree (manifestBytes);
        require (!contains (manifestBytes, capabilityValue), "Capability value leaked into the manifest.");
        require (manifest.path ("schema_version").asInt () == 1, "Wrong manifest schema.");
        require (manifest.path ("protocol_version").asInt () == 1, "Wrong protocol version.");
        require ("ipv4-loopback".equals (manifest.path ("transport").asText ()), "Wrong transport.");
        require (manifest.path ("port").asInt () == PushwigRuntimeRendezvous.DEFAULT_PORT, "Wrong manifest port.");
        require (manifest.path ("capability_file").asText ().equals (capability.getFileName ().toString ()), "Wrong capability basename.");
        require (manifest.path ("session_generation").asText ().equals (rendezvous.getGeneration ()), "Wrong generation.");
        require (manifest.path ("owner_pid").asLong () == ProcessHandle.current ().pid (), "Wrong owner PID.");
        java.util.Arrays.fill (capabilityValue, (byte) 0);
        require (rendezvous.receiverTerminated (), "Receiver-terminal cleanup failed.");
        require (!Files.exists (rendezvous.getManifestPath (), LinkOption.NOFOLLOW_LINKS), "Manifest survived receiver termination.");
        require (!Files.exists (capability, LinkOption.NOFOLLOW_LINKS), "Capability survived receiver termination.");
        rendezvous.close ();
    }


    private static void testSingleOwnerLock () throws Exception
    {
        final PushwigRuntimeRendezvous owner = PushwigRuntimeRendezvous.prepare ();
        expectIOException (PushwigRuntimeRendezvous::prepare, "A second owner acquired the runtime lock.");
        owner.close ();
    }


    private static void testSafeStaleCleanup () throws Exception
    {
        final PushwigRuntimeRendezvous first = PushwigRuntimeRendezvous.prepare ();
        final Path root = first.getRuntimeRoot ();
        first.close ();

        final String generation = "0123456789abcdef0123456789abcdef";
        final Path staleCapability = root.resolve ("capability-" + generation + ".hex");
        writePrivate (staleCapability, "0".repeat (64));
        final Path staleTemporary = root.resolve ("current-" + generation + ".tmp");
        writePrivate (staleTemporary, "{}");
        final String staleManifest = """
            {
              "schema_version": 1,
              "protocol_version": 1,
              "transport": "ipv4-loopback",
              "port": 45291,
              "capability_file": "capability-%s.hex",
              "session_generation": "%s",
              "owner_pid": 9223372036854775807,
              "owner_start_epoch_millis": 1
            }
            """.formatted (generation, generation);
        writePrivate (root.resolve ("current.json"), staleManifest);

        final PushwigRuntimeRendezvous replacement = PushwigRuntimeRendezvous.prepare ();
        require (!Files.exists (staleCapability, LinkOption.NOFOLLOW_LINKS), "Stale capability was not removed.");
        require (!Files.exists (staleTemporary, LinkOption.NOFOLLOW_LINKS), "Stale manifest temporary was not removed.");
        replacement.close ();
    }


    private static void testUnsafeEntryRefusal () throws Exception
    {
        final PushwigRuntimeRendezvous first = PushwigRuntimeRendezvous.prepare ();
        final Path root = first.getRuntimeRoot ();
        first.close ();

        final Path unknown = root.resolve ("unexpected-entry");
        writePrivate (unknown, "unsafe");
        expectIOException (PushwigRuntimeRendezvous::prepare, "An unknown runtime entry was accepted.");
        Files.delete (unknown);

        final Path malformedManifest = root.resolve ("current.json");
        writePrivate (malformedManifest, "{}");
        expectIOException (PushwigRuntimeRendezvous::prepare, "A malformed current manifest was accepted.");
        Files.delete (malformedManifest);

        writePrivate (malformedManifest, validDeadManifest ("cccccccccccccccccccccccccccccccc") + "{}");
        expectIOException (PushwigRuntimeRendezvous::prepare, "Trailing manifest content was accepted.");
        Files.delete (malformedManifest);

        final Path wrongModeCapability = root.resolve ("capability-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.hex");
        writePrivate (wrongModeCapability, "0".repeat (64));
        Files.setPosixFilePermissions (wrongModeCapability, PosixFilePermissions.fromString ("rw-r--r--"));
        expectIOException (PushwigRuntimeRendezvous::prepare, "An unsafe capability mode was accepted.");
        Files.delete (wrongModeCapability);

        final Path target = Path.of (System.getProperty ("user.home")).resolve ("symlink-target");
        writePrivate (target, "unsafe");
        final Path symbolicLink = root.resolve ("capability-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.hex");
        Files.createSymbolicLink (symbolicLink, target);
        expectIOException (PushwigRuntimeRendezvous::prepare, "A capability symlink was accepted.");
        Files.delete (symbolicLink);
        Files.delete (target);

        Files.setPosixFilePermissions (root, PosixFilePermissions.fromString ("rwxr-xr-x"));
        expectIOException (PushwigRuntimeRendezvous::prepare, "An unsafe runtime-root mode was accepted.");
        Files.setPosixFilePermissions (root, PRIVATE_DIRECTORY);
    }


    private static void testManifestPublicationCollisionCleanup () throws Exception
    {
        final PushwigRuntimeRendezvous rendezvous = PushwigRuntimeRendezvous.prepare ();
        final Path manifest = rendezvous.getManifestPath ();
        writePrivate (manifest, "{}");
        expectIOException (() -> {

            rendezvous.publish (PushwigRuntimeRendezvous.DEFAULT_PORT);
            return null;

        }, "Manifest collision was accepted.");
        Files.delete (manifest);
        final Path capability = rendezvous.getCapabilityPath ();
        rendezvous.close ();
        require (!Files.exists (capability, LinkOption.NOFOLLOW_LINKS), "Capability survived failed publication cleanup.");
    }


    private static void writePrivate (final Path path, final String value) throws IOException
    {
        Files.writeString (path, value, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions (path, PRIVATE_FILE);
    }


    private static String validDeadManifest (final String generation)
    {
        return """
            {
              "schema_version": 1,
              "protocol_version": 1,
              "transport": "ipv4-loopback",
              "port": 45291,
              "capability_file": "capability-%s.hex",
              "session_generation": "%s",
              "owner_pid": 9223372036854775807,
              "owner_start_epoch_millis": 1
            }
            """.formatted (generation, generation);
    }


    private static boolean contains (final byte [] haystack, final byte [] needle)
    {
        for (int start = 0; start <= haystack.length - needle.length; start++)
        {
            int index = 0;
            while (index < needle.length && haystack[start + index] == needle[index])
                index++;
            if (index == needle.length)
                return true;
        }
        return false;
    }


    private static void expectIOException (final CheckedSupplier action, final String message) throws Exception
    {
        try
        {
            action.get ();
        }
        catch (final IOException ex)
        {
            return;
        }
        throw new AssertionError (message);
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
    private interface CheckedSupplier
    {
        Object get () throws Exception;
    }
}
