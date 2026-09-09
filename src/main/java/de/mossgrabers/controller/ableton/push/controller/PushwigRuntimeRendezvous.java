// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V5A ordinary external-ingress activation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;


/**
 * Owns one private capability and one non-secret current-session rendezvous for an external raster
 * receiver. The lifetime lock deliberately permits only one active Pushwig ingress in a user
 * account.
 *
 * @author Peter Kassel
 */
final class PushwigRuntimeRendezvous implements AutoCloseable
{
    static final int  SCHEMA_VERSION   = 1;
    static final int  PROTOCOL_VERSION = 1;
    static final int  DEFAULT_PORT     = 45291;
    static final int  STALE_MILLIS     = 1500;

    private static final String                    TRANSPORT             = "ipv4-loopback";
    private static final String                    CURRENT_MANIFEST      = "current.json";
    private static final String                    OWNER_LOCK            = "owner.lock";
    private static final int                       MAX_MANIFEST_BYTES     = 4096;
    private static final int                       GENERATION_BYTES       = 16;
    private static final int                       CAPABILITY_BYTES       = 32;
    private static final int                       CREATE_ATTEMPTS        = 8;
    private static final Pattern                   GENERATION_PATTERN     = Pattern.compile ("[0-9a-f]{32}");
    private static final Pattern                   CAPABILITY_PATTERN     = Pattern.compile ("capability-[0-9a-f]{32}\\.hex");
    private static final Pattern                   TEMP_MANIFEST_PATTERN  = Pattern.compile ("current-[0-9a-f]{32}\\.tmp");
    private static final Set<PosixFilePermission>  PRIVATE_DIRECTORY_MODE = Set.of (PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission>  PRIVATE_FILE_MODE      = Set.of (PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final ObjectMapper              MAPPER                 = new ObjectMapper ().enable (JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable (DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final SecureRandom              RANDOM                 = new SecureRandom ();

    private final Path                             runtimeRoot;
    private final Path                             manifestPath;
    private final Path                             temporaryManifestPath;
    private final Path                             capabilityPath;
    private final String                           generation;
    private final UserPrincipal                    owner;
    private final FileChannel                      lockChannel;
    private final FileLock                         ownerLock;

    private boolean                                manifestPublished;
    private boolean                                closed;


    private PushwigRuntimeRendezvous (final Path runtimeRoot, final String generation, final UserPrincipal owner, final FileChannel lockChannel, final FileLock ownerLock, final Path capabilityPath)
    {
        this.runtimeRoot = runtimeRoot;
        this.manifestPath = runtimeRoot.resolve (CURRENT_MANIFEST);
        this.temporaryManifestPath = runtimeRoot.resolve ("current-" + generation + ".tmp");
        this.capabilityPath = capabilityPath;
        this.generation = generation;
        this.owner = owner;
        this.lockChannel = lockChannel;
        this.ownerLock = ownerLock;
    }


    static PushwigRuntimeRendezvous prepare () throws IOException
    {
        final String homeValue = System.getProperty ("user.home");
        if (homeValue == null || homeValue.isBlank ())
            throw new IOException ("The current user home is unavailable.");

        final Path home = Path.of (homeValue).toAbsolutePath ().normalize ();
        final BasicFileAttributes homeAttributes = Files.readAttributes (home, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!homeAttributes.isDirectory () || Files.isSymbolicLink (home))
            throw new IOException ("The current user home is not a real directory.");

        final UserPrincipal owner = Files.getOwner (home, LinkOption.NOFOLLOW_LINKS);
        final Path pushwigRoot = home.resolve (".pushwig");
        final Path runtimeParent = pushwigRoot.resolve ("runtime");
        final Path runtimeRoot = runtimeParent.resolve ("external-raster-v1");
        ensurePrivateDirectory (pushwigRoot, owner);
        ensurePrivateDirectory (runtimeParent, owner);
        ensurePrivateDirectory (runtimeRoot, owner);

        final Path lockPath = runtimeRoot.resolve (OWNER_LOCK);
        createPrivateFileIfMissing (lockPath);
        validatePrivateRegularFile (lockPath, owner, -1);

        FileChannel lockChannel = null;
        FileLock ownerLock = null;
        try
        {
            lockChannel = FileChannel.open (lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try
            {
                ownerLock = lockChannel.tryLock ();
            }
            catch (final OverlappingFileLockException ex)
            {
                throw new IOException ("Another Pushwig ingress owner is active.", ex);
            }
            if (ownerLock == null)
                throw new IOException ("Another Pushwig ingress owner is active.");

            removeSafeStaleEntries (runtimeRoot, owner);
            return createCapability (runtimeRoot, owner, lockChannel, ownerLock);
        }
        catch (final IOException | RuntimeException ex)
        {
            closeQuietly (ownerLock);
            closeQuietly (lockChannel);
            throw ex;
        }
    }


    synchronized void publish (final int port) throws IOException
    {
        if (this.closed || this.manifestPublished)
            throw new IOException ("The Pushwig rendezvous is not publishable.");
        if (port < 1024 || port > 65535)
            throw new IOException ("The receiver port is invalid.");

        validatePrivateRegularFile (this.capabilityPath, this.owner, CAPABILITY_BYTES * 2);
        final ProcessHandle currentProcess = ProcessHandle.current ();
        final Optional<Instant> startInstant = currentProcess.info ().startInstant ();
        if (startInstant.isEmpty ())
            throw new IOException ("The owner process start time is unavailable.");

        final ObjectNode manifest = MAPPER.createObjectNode ();
        manifest.put ("schema_version", SCHEMA_VERSION);
        manifest.put ("protocol_version", PROTOCOL_VERSION);
        manifest.put ("transport", TRANSPORT);
        manifest.put ("port", port);
        manifest.put ("capability_file", this.capabilityPath.getFileName ().toString ());
        manifest.put ("session_generation", this.generation);
        manifest.put ("owner_pid", currentProcess.pid ());
        manifest.put ("owner_start_epoch_millis", startInstant.get ().toEpochMilli ());

        byte [] manifestBytes = null;
        boolean moved = false;
        try
        {
            manifestBytes = MAPPER.writerWithDefaultPrettyPrinter ().writeValueAsBytes (manifest);
            if (manifestBytes.length > MAX_MANIFEST_BYTES)
                throw new IOException ("The current-session manifest is too large.");
            if (Files.exists (this.manifestPath, LinkOption.NOFOLLOW_LINKS))
                throw new IOException ("A current-session manifest already exists.");
            writePrivateFile (this.temporaryManifestPath, manifestBytes);
            try
            {
                Files.move (this.temporaryManifestPath, this.manifestPath, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            }
            catch (final AtomicMoveNotSupportedException ex)
            {
                throw new IOException ("Atomic current-session publication is unavailable.", ex);
            }
            validatePrivateRegularFile (this.manifestPath, this.owner, -1);
            this.manifestPublished = true;
        }
        finally
        {
            if (manifestBytes != null)
                Arrays.fill (manifestBytes, (byte) 0);
            if (!this.manifestPublished)
            {
                Files.deleteIfExists (this.temporaryManifestPath);
                if (moved)
                    Files.deleteIfExists (this.manifestPath);
            }
        }
    }


    synchronized boolean invalidateCurrentManifest ()
    {
        if (!this.manifestPublished)
            return true;
        try
        {
            if (Files.exists (this.manifestPath, LinkOption.NOFOLLOW_LINKS))
            {
                final JsonNode manifest = readAndValidateManifest (this.manifestPath, this.owner);
                if (!this.generation.equals (manifest.path ("session_generation").textValue ()))
                    return false;
                Files.delete (this.manifestPath);
            }
            this.manifestPublished = false;
            return true;
        }
        catch (final IOException | RuntimeException ex)
        {
            return false;
        }
    }


    synchronized boolean receiverTerminated ()
    {
        boolean result = this.invalidateCurrentManifest ();
        try
        {
            Files.deleteIfExists (this.capabilityPath);
        }
        catch (final IOException | RuntimeException ex)
        {
            result = false;
        }
        try
        {
            Files.deleteIfExists (this.temporaryManifestPath);
        }
        catch (final IOException | RuntimeException ex)
        {
            result = false;
        }
        return result;
    }


    @Override
    public synchronized void close () throws IOException
    {
        if (this.closed)
            return;

        IOException failure = this.invalidateCurrentManifest () ? null : new IOException ("Could not invalidate the current Pushwig rendezvous.");
        try
        {
            Files.deleteIfExists (this.temporaryManifestPath);
        }
        catch (final IOException ex)
        {
            failure = appendFailure (failure, ex);
        }
        try
        {
            Files.deleteIfExists (this.capabilityPath);
        }
        catch (final IOException ex)
        {
            failure = appendFailure (failure, ex);
        }
        try
        {
            this.ownerLock.close ();
        }
        catch (final IOException ex)
        {
            failure = appendFailure (failure, ex);
        }
        try
        {
            this.lockChannel.close ();
        }
        catch (final IOException ex)
        {
            failure = appendFailure (failure, ex);
        }
        this.closed = true;
        if (failure != null)
            throw failure;
    }


    Path getRuntimeRoot ()
    {
        return this.runtimeRoot;
    }


    Path getCapabilityPath ()
    {
        return this.capabilityPath;
    }


    Path getManifestPath ()
    {
        return this.manifestPath;
    }


    String getGeneration ()
    {
        return this.generation;
    }


    synchronized boolean isManifestPublished ()
    {
        return this.manifestPublished;
    }


    private static PushwigRuntimeRendezvous createCapability (final Path runtimeRoot, final UserPrincipal owner, final FileChannel lockChannel, final FileLock ownerLock) throws IOException
    {
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++)
        {
            final byte [] generationBytes = new byte [GENERATION_BYTES];
            final byte [] capability = new byte [CAPABILITY_BYTES];
            byte [] capabilityHex = null;
            try
            {
                RANDOM.nextBytes (generationBytes);
                RANDOM.nextBytes (capability);
                final String generation = hexadecimalString (generationBytes);
                final Path capabilityPath = runtimeRoot.resolve ("capability-" + generation + ".hex");
                capabilityHex = hexadecimalBytes (capability);
                try
                {
                    writePrivateFile (capabilityPath, capabilityHex);
                    validatePrivateRegularFile (capabilityPath, owner, CAPABILITY_BYTES * 2);
                    return new PushwigRuntimeRendezvous (runtimeRoot, generation, owner, lockChannel, ownerLock, capabilityPath);
                }
                catch (final FileAlreadyExistsException ex)
                {
                    // A cryptographic generation collision is retried a fixed number of times.
                }
                catch (final IOException | RuntimeException ex)
                {
                    deleteQuietly (capabilityPath);
                    throw ex;
                }
            }
            finally
            {
                Arrays.fill (generationBytes, (byte) 0);
                Arrays.fill (capability, (byte) 0);
                if (capabilityHex != null)
                    Arrays.fill (capabilityHex, (byte) 0);
            }
        }
        throw new IOException ("Could not create a fresh Pushwig capability.");
    }


    private static void removeSafeStaleEntries (final Path runtimeRoot, final UserPrincipal owner) throws IOException
    {
        final List<Path> staleCapabilities = new ArrayList<> ();
        final List<Path> staleTemporaryManifests = new ArrayList<> ();
        Path staleManifest = null;
        JsonNode manifest = null;

        try (DirectoryStream<Path> entries = Files.newDirectoryStream (runtimeRoot))
        {
            for (final Path entry: entries)
            {
                final String name = entry.getFileName ().toString ();
                if (OWNER_LOCK.equals (name))
                    continue;
                if (CURRENT_MANIFEST.equals (name))
                {
                    if (staleManifest != null)
                        throw new IOException ("Multiple current-session manifests exist.");
                    manifest = readAndValidateManifest (entry, owner);
                    staleManifest = entry;
                    continue;
                }
                if (CAPABILITY_PATTERN.matcher (name).matches ())
                {
                    validatePrivateRegularFile (entry, owner, CAPABILITY_BYTES * 2);
                    staleCapabilities.add (entry);
                    continue;
                }
                if (TEMP_MANIFEST_PATTERN.matcher (name).matches ())
                {
                    validatePrivateRegularFile (entry, owner, -1);
                    if (Files.size (entry) > MAX_MANIFEST_BYTES)
                        throw new IOException ("A stale manifest temporary file is too large.");
                    staleTemporaryManifests.add (entry);
                    continue;
                }
                throw new IOException ("The Pushwig runtime root contains an unknown entry.");
            }
        }

        if (manifest != null && isManifestOwnerAlive (manifest))
            throw new IOException ("Another Pushwig session still appears live.");

        if (staleManifest != null)
            Files.delete (staleManifest);
        for (final Path path: staleTemporaryManifests)
            Files.delete (path);
        for (final Path path: staleCapabilities)
            Files.delete (path);
    }


    private static JsonNode readAndValidateManifest (final Path path, final UserPrincipal owner) throws IOException
    {
        validatePrivateRegularFile (path, owner, -1);
        final byte [] manifestBytes = new byte [MAX_MANIFEST_BYTES];
        final JsonNode manifest;
        try (SeekableByteChannel channel = Files.newByteChannel (path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            final long size = channel.size ();
            if (size < 2 || size > MAX_MANIFEST_BYTES)
                throw new IOException ("The current-session manifest size is invalid.");
            final ByteBuffer destination = ByteBuffer.wrap (manifestBytes, 0, (int) size);
            while (destination.hasRemaining ())
            {
                final int count = channel.read (destination);
                if (count < 0)
                    throw new IOException ("The current-session manifest was truncated.");
            }
            if (channel.size () != size)
                throw new IOException ("The current-session manifest changed while it was read.");
            manifest = MAPPER.readTree (manifestBytes, 0, (int) size);
        }
        final Set<String> expectedFields = Set.of ("schema_version", "protocol_version", "transport", "port", "capability_file", "session_generation", "owner_pid", "owner_start_epoch_millis");
        if (manifest == null || !manifest.isObject () || manifest.size () != expectedFields.size ())
            throw new IOException ("The current-session manifest schema is invalid.");
        final Set<String> actualFields = new HashSet<> ();
        manifest.fieldNames ().forEachRemaining (actualFields::add);
        if (!actualFields.equals (expectedFields))
            throw new IOException ("The current-session manifest fields are invalid.");

        final String transport = textValue (manifest, "transport");
        final String capabilityFile = textValue (manifest, "capability_file");
        final String generation = textValue (manifest, "session_generation");
        final int port = integerValue (manifest, "port");
        final long ownerPID = longValue (manifest, "owner_pid");
        final long ownerStart = longValue (manifest, "owner_start_epoch_millis");
        if (integerValue (manifest, "schema_version") != SCHEMA_VERSION || integerValue (manifest, "protocol_version") != PROTOCOL_VERSION || !TRANSPORT.equals (transport) || port < 1024 || port > 65535 || !GENERATION_PATTERN.matcher (generation).matches () || !capabilityFile.equals ("capability-" + generation + ".hex") || ownerPID < 1 || ownerStart < 1)
            throw new IOException ("The current-session manifest values are invalid.");
        return manifest;
    }


    private static boolean isManifestOwnerAlive (final JsonNode manifest) throws IOException
    {
        final long ownerPID = longValue (manifest, "owner_pid");
        final long ownerStart = longValue (manifest, "owner_start_epoch_millis");
        final Optional<ProcessHandle> process = ProcessHandle.of (ownerPID);
        if (process.isEmpty () || !process.get ().isAlive ())
            return false;
        final Optional<Instant> start = process.get ().info ().startInstant ();
        return start.isEmpty () || start.get ().toEpochMilli () == ownerStart;
    }


    private static String textValue (final JsonNode manifest, final String name) throws IOException
    {
        final JsonNode value = manifest.get (name);
        if (value == null || !value.isTextual ())
            throw new IOException ("The current-session manifest value type is invalid.");
        return value.textValue ();
    }


    private static int integerValue (final JsonNode manifest, final String name) throws IOException
    {
        final JsonNode value = manifest.get (name);
        if (value == null || !value.isIntegralNumber () || !value.canConvertToInt ())
            throw new IOException ("The current-session manifest value type is invalid.");
        return value.intValue ();
    }


    private static long longValue (final JsonNode manifest, final String name) throws IOException
    {
        final JsonNode value = manifest.get (name);
        if (value == null || !value.isIntegralNumber () || !value.canConvertToLong ())
            throw new IOException ("The current-session manifest value type is invalid.");
        return value.longValue ();
    }


    private static void ensurePrivateDirectory (final Path path, final UserPrincipal owner) throws IOException
    {
        if (!Files.exists (path, LinkOption.NOFOLLOW_LINKS))
        {
            try
            {
                Files.createDirectory (path, PosixFilePermissions.asFileAttribute (PRIVATE_DIRECTORY_MODE));
            }
            catch (final FileAlreadyExistsException ex)
            {
                // Another same-user setup may have created it; the exact state is validated below.
            }
        }
        final BasicFileAttributes attributes = Files.readAttributes (path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory () || Files.isSymbolicLink (path) || !owner.equals (Files.getOwner (path, LinkOption.NOFOLLOW_LINKS)) || !PRIVATE_DIRECTORY_MODE.equals (Files.getPosixFilePermissions (path, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException ("A Pushwig runtime directory is unsafe.");
    }


    private static void createPrivateFileIfMissing (final Path path) throws IOException
    {
        try
        {
            Files.createFile (path, PosixFilePermissions.asFileAttribute (PRIVATE_FILE_MODE));
        }
        catch (final FileAlreadyExistsException ex)
        {
            // Existing state is validated by the caller without changing its permissions.
        }
    }


    private static void writePrivateFile (final Path path, final byte [] contents) throws IOException
    {
        Files.createFile (path, PosixFilePermissions.asFileAttribute (PRIVATE_FILE_MODE));
        boolean complete = false;
        try (FileChannel channel = FileChannel.open (path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
        {
            final ByteBuffer buffer = ByteBuffer.wrap (contents);
            while (buffer.hasRemaining ())
                channel.write (buffer);
            channel.force (true);
            complete = true;
        }
        finally
        {
            if (!complete)
                Files.deleteIfExists (path);
        }
    }


    private static void validatePrivateRegularFile (final Path path, final UserPrincipal owner, final long expectedSize) throws IOException
    {
        if (Files.isSymbolicLink (path))
            throw new IOException ("A Pushwig runtime file is a symbolic link.");
        final BasicFileAttributes attributes = Files.readAttributes (path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile () || expectedSize >= 0 && attributes.size () != expectedSize || !owner.equals (Files.getOwner (path, LinkOption.NOFOLLOW_LINKS)) || !PRIVATE_FILE_MODE.equals (Files.getPosixFilePermissions (path, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException ("A Pushwig runtime file is unsafe.");
    }


    private static String hexadecimalString (final byte [] bytes)
    {
        return new String (hexadecimalBytes (bytes), java.nio.charset.StandardCharsets.US_ASCII);
    }


    private static byte [] hexadecimalBytes (final byte [] bytes)
    {
        final byte [] result = new byte [bytes.length * 2];
        final byte [] digits = "0123456789abcdef".getBytes (java.nio.charset.StandardCharsets.US_ASCII);
        for (int index = 0; index < bytes.length; index++)
        {
            final int value = bytes[index] & 0xFF;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0F];
        }
        return result;
    }


    private static void deleteQuietly (final Path path)
    {
        try
        {
            Files.deleteIfExists (path);
        }
        catch (final IOException | RuntimeException ex)
        {
            // Receiver authority is already closed; a bounded stale file is handled next start.
        }
    }


    private static IOException appendFailure (final IOException failure, final IOException additional)
    {
        if (failure == null)
            return additional;
        failure.addSuppressed (additional);
        return failure;
    }


    private static void closeQuietly (final AutoCloseable closeable)
    {
        if (closeable == null)
            return;
        try
        {
            closeable.close ();
        }
        catch (final Exception ex)
        {
            // The OS releases the lock and channels when the owning process ends.
        }
    }
}
