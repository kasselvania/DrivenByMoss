// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2025
// Pushwig V1D-2 external-ingress implementation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.daw.IHost;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Set;


/**
 * One-thread, capability-authenticated IPv4-loopback receiver for external Push raster frames.
 *
 * @author Peter Kassel
 */
final class ExternalRasterReceiver implements Runnable
{
    static final int  MAGIC                = 0x50575852;
    static final int  VERSION              = 1;
    static final int  HEADER_LENGTH        = 80;
    static final int  MESSAGE_HELLO        = 1;
    static final int  MESSAGE_FRAME        = 2;
    static final int  MESSAGE_CLEAR        = 3;
    static final int  FORMAT_NONE          = 0;
    static final int  FORMAT_OPAQUE_BGRA   = 1;
    static final int  TOKEN_BYTES          = 32;
    static final long RECEIVER_JOIN_MILLIS = 2000;

    private static final int                      FRAME_WIDTH         = 960;
    private static final int                      FRAME_HEIGHT        = 160;
    private static final int                      TOKEN_HEX_BYTES     = TOKEN_BYTES * 2;
    private static final int                      MAX_TOKEN_FILE_BYTES = 128;
    private static final Set<PosixFilePermission> PRIVATE_FILE_MODE   = Set.of (PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final IHost                          host;
    private final LatestExternalRasterFrameStore store;
    private final byte []                        token;
    private final byte []                        header  = new byte [HEADER_LENGTH];
    private final byte []                        staging = new byte [LatestExternalRasterFrameStore.MAX_PAYLOAD_BYTES];
    private final ServerSocket                   serverSocket;
    private final Thread                         receiverThread;
    private final int                            port;
    private final Runnable                       terminationCallback;

    private volatile Socket                      clientSocket;
    private volatile boolean                     closing;

    private long                                 generationCounter;
    private volatile long                        acceptedFrames;
    private volatile long                        acceptedSessions;
    private volatile long                        sequenceGapEvents;
    private volatile long                        sequenceRejects;
    private volatile long                        authenticationRejects;
    private volatile long                        malformedRejects;
    private volatile long                        truncatedHeaders;
    private volatile long                        truncatedPayloads;
    private volatile long                        disconnects;


    private ExternalRasterReceiver (final IHost host, final LatestExternalRasterFrameStore store, final byte [] token, final ServerSocket serverSocket, final int port, final Runnable terminationCallback)
    {
        this.host = host;
        this.store = store;
        this.token = token;
        this.serverSocket = serverSocket;
        this.port = port;
        this.terminationCallback = terminationCallback;
        this.receiverThread = new Thread (this, "Pushwig External Raster Receiver");
        this.receiverThread.setDaemon (true);
    }


    static ExternalRasterReceiver start (final IHost host, final LatestExternalRasterFrameStore store, final int port, final String tokenPath, final Runnable terminationCallback)
    {
        if (terminationCallback == null)
            return null;
        final byte [] token = loadToken (tokenPath);
        if (token == null)
            return null;

        ServerSocket serverSocket = null;
        try
        {
            serverSocket = new ServerSocket ();
            serverSocket.setReuseAddress (true);
            serverSocket.bind (new InetSocketAddress (InetAddress.getByAddress (new byte []
            {
                127,
                0,
                0,
                1
            }), port), 1);

            final ExternalRasterReceiver receiver = new ExternalRasterReceiver (host, store, token, serverSocket, port, terminationCallback);
            receiver.receiverThread.start ();
            host.println ("Pushwig: external raster ingress listening on IPv4 loopback port " + port + ".");
            return receiver;
        }
        catch (final IOException | RuntimeException ex)
        {
            closeQuietly (serverSocket);
            Arrays.fill (token, (byte) 0);
            return null;
        }
    }


    @Override
    public void run ()
    {
        try
        {
            while (!this.closing)
            {
                final Socket socket;
                try
                {
                    socket = this.serverSocket.accept ();
                }
                catch (final IOException | RuntimeException ex)
                {
                    if (!this.closing)
                        this.host.error ("Pushwig external raster receiver failed while accepting a connection.", ex);
                    break;
                }

                this.clientSocket = socket;
                if (this.closing)
                {
                    closeQuietly (socket);
                    break;
                }

                try
                {
                    socket.setTcpNoDelay (true);
                    this.handleConnection (socket);
                }
                catch (final IOException ex)
                {
                    // Peer close/reset is a connection lifecycle event; authority is revoked in the
                    // connection finally block and the one receiver returns to accept.
                }
                catch (final RuntimeException ex)
                {
                    if (!this.closing)
                        this.host.error ("Pushwig external raster receiver failed.", ex);
                    break;
                }
                finally
                {
                    closeQuietly (socket);
                    if (this.clientSocket == socket)
                        this.clientSocket = null;
                }
            }
        }
        finally
        {
            this.store.close ();
            closeQuietly (this.clientSocket);
            this.clientSocket = null;
            closeQuietly (this.serverSocket);
            Arrays.fill (this.token, (byte) 0);
            try
            {
                this.terminationCallback.run ();
            }
            catch (final RuntimeException ex)
            {
                this.host.error ("Pushwig external raster receiver termination cleanup failed.", ex);
            }
        }
    }


    void beginShutdown ()
    {
        this.closing = true;
        this.store.close ();
        Arrays.fill (this.token, (byte) 0);
        closeQuietly (this.clientSocket);
        closeQuietly (this.serverSocket);
    }


    void awaitShutdown ()
    {
        try
        {
            this.receiverThread.join (RECEIVER_JOIN_MILLIS);
            if (this.receiverThread.isAlive ())
                this.host.error ("Pushwig external raster receiver did not end in 2 seconds.");
        }
        catch (final InterruptedException ex)
        {
            this.host.error ("Pushwig external raster receiver shutdown interrupted.", ex);
            Thread.currentThread ().interrupt ();
        }
        finally
        {
            Arrays.fill (this.token, (byte) 0);
        }
    }


    boolean isRunning ()
    {
        return this.receiverThread.isAlive () && !this.closing;
    }


    int getPort ()
    {
        return this.port;
    }


    private void handleConnection (final Socket socket) throws IOException
    {
        final InputStream input = socket.getInputStream ();
        long generation = 0;
        boolean authenticated = false;
        try
        {
            final int helloHeaderBytes = readExact (input, this.header, HEADER_LENGTH);
            if (helloHeaderBytes < HEADER_LENGTH)
            {
                if (helloHeaderBytes > 0)
                    this.truncatedHeaders++;
                return;
            }

            final long sessionHigh = getLong (this.header, 24);
            final long sessionLow = getLong (this.header, 32);
            if (!this.isCommonHeaderValid () || getInt (this.header, 8) != MESSAGE_HELLO || getInt (this.header, 16) != FORMAT_NONE || getLong (this.header, 40) != 0 || sessionHigh == 0 && sessionLow == 0 || !hasZeroGeometry (this.header) || getInt (this.header, 68) != TOKEN_BYTES)
            {
                this.authenticationRejects++;
                return;
            }

            final int tokenBytes = readExact (input, this.staging, TOKEN_BYTES);
            final boolean tokenMatches = tokenBytes == TOKEN_BYTES && constantTimeTokenEquals (this.token, this.staging);
            Arrays.fill (this.staging, 0, TOKEN_BYTES, (byte) 0);
            if (tokenBytes < TOKEN_BYTES)
            {
                this.truncatedPayloads++;
                return;
            }
            if (!tokenMatches)
            {
                this.authenticationRejects++;
                return;
            }

            generation = this.nextGeneration ();
            this.store.beginSession (generation);
            this.acceptedSessions++;
            authenticated = true;
            long lastSequence = 0;
            boolean sequenceExhausted = false;

            while (!this.closing)
            {
                final int headerBytes = readExact (input, this.header, HEADER_LENGTH);
                if (headerBytes < 0)
                    return;
                if (headerBytes < HEADER_LENGTH)
                {
                    this.truncatedHeaders++;
                    return;
                }

                if (!this.isCommonHeaderValid () || getLong (this.header, 24) != sessionHigh || getLong (this.header, 32) != sessionLow)
                {
                    this.malformedRejects++;
                    return;
                }

                final int messageType = getInt (this.header, 8);
                final int format = getInt (this.header, 16);
                final long sequence = getLong (this.header, 40);
                final int destinationX = getInt (this.header, 48);
                final int destinationY = getInt (this.header, 52);
                final int width = getInt (this.header, 56);
                final int height = getInt (this.header, 60);
                final int stride = getInt (this.header, 64);
                final int payloadLength = getInt (this.header, 68);

                if (sequenceExhausted || sequence <= 0 || sequence <= lastSequence)
                {
                    this.sequenceRejects++;
                    this.store.invalidateSession (generation);
                    return;
                }
                if (lastSequence > 0 && sequence - lastSequence > 1)
                    this.sequenceGapEvents++;

                if (messageType == MESSAGE_CLEAR)
                {
                    if (format != FORMAT_NONE || destinationX != 0 || destinationY != 0 || width != 0 || height != 0 || stride != 0 || payloadLength != 0)
                    {
                        this.malformedRejects++;
                        return;
                    }
                    lastSequence = sequence;
                    sequenceExhausted = sequence == Long.MAX_VALUE;
                    this.store.clear (generation);
                    continue;
                }

                if (messageType != MESSAGE_FRAME || format != FORMAT_OPAQUE_BGRA || !isValidFrameMetadata (destinationX, destinationY, width, height, stride, payloadLength))
                {
                    this.malformedRejects++;
                    return;
                }

                final int payloadBytes = readExact (input, this.staging, payloadLength);
                if (payloadBytes < payloadLength)
                {
                    this.truncatedPayloads++;
                    return;
                }
                if (!hasOpaqueAlpha (this.staging, stride, width, height))
                {
                    this.malformedRejects++;
                    return;
                }

                final long receiptNanos = System.nanoTime ();
                if (!this.store.publish (generation, this.staging, payloadLength, stride, destinationX, destinationY, width, height, sequence, receiptNanos))
                    return;
                lastSequence = sequence;
                sequenceExhausted = sequence == Long.MAX_VALUE;
                this.acceptedFrames++;
            }
        }
        finally
        {
            if (authenticated)
            {
                this.store.invalidateSession (generation);
                this.disconnects++;
            }
        }
    }


    private long nextGeneration ()
    {
        this.generationCounter++;
        if (this.generationCounter == 0)
            this.generationCounter++;
        return this.generationCounter;
    }


    private boolean isCommonHeaderValid ()
    {
        return getInt (this.header, 0) == MAGIC && getUnsignedShort (this.header, 4) == VERSION && getUnsignedShort (this.header, 6) == HEADER_LENGTH && getInt (this.header, 12) == 0 && getInt (this.header, 20) == 0 && getLong (this.header, 72) == 0;
    }


    private static boolean hasZeroGeometry (final byte [] bytes)
    {
        return getInt (bytes, 48) == 0 && getInt (bytes, 52) == 0 && getInt (bytes, 56) == 0 && getInt (bytes, 60) == 0 && getInt (bytes, 64) == 0;
    }


    private static boolean isValidFrameMetadata (final int destinationX, final int destinationY, final int width, final int height, final int stride, final int payloadLength)
    {
        if (destinationX < 0 || destinationY < 0 || width < 1 || height < 1 || stride < 1 || payloadLength < 1 || payloadLength > LatestExternalRasterFrameStore.MAX_PAYLOAD_BYTES)
            return false;

        final long rowBytes = (long) width * 4;
        final long destinationRight = (long) destinationX + width;
        final long destinationBottom = (long) destinationY + height;
        final long expectedPayload = (long) (height - 1) * stride + rowBytes;
        return rowBytes <= Integer.MAX_VALUE && stride >= rowBytes && destinationRight <= FRAME_WIDTH && destinationBottom <= FRAME_HEIGHT && expectedPayload > 0 && expectedPayload <= LatestExternalRasterFrameStore.MAX_PAYLOAD_BYTES && expectedPayload == payloadLength;
    }


    private static boolean hasOpaqueAlpha (final byte [] bytes, final int stride, final int width, final int height)
    {
        final int rowBytes = width * 4;
        int rowStart = 0;
        for (int row = 0; row < height; row++)
        {
            final int rowEnd = rowStart + rowBytes;
            for (int alpha = rowStart + 3; alpha < rowEnd; alpha += 4)
            {
                if (bytes[alpha] != (byte) 0xFF)
                    return false;
            }
            rowStart += stride;
        }
        return true;
    }


    private static int readExact (final InputStream input, final byte [] destination, final int length) throws IOException
    {
        int total = 0;
        while (total < length)
        {
            final int count = input.read (destination, total, length - total);
            if (count < 0)
                return total == 0 ? -1 : total;
            if (count > 0)
                total += count;
        }
        return total;
    }


    private static byte [] loadToken (final String tokenPath)
    {
        if (tokenPath == null || tokenPath.isBlank ())
            return null;

        final byte [] fileBytes = new byte [MAX_TOKEN_FILE_BYTES];
        byte [] result = null;
        try
        {
            final Path path = Path.of (tokenPath);
            if (Files.isSymbolicLink (path))
                return null;

            final BasicFileAttributes attributes = Files.readAttributes (path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile () || attributes.size () < TOKEN_HEX_BYTES || attributes.size () > MAX_TOKEN_FILE_BYTES)
                return null;
            if (!Files.getPosixFilePermissions (path, LinkOption.NOFOLLOW_LINKS).equals (PRIVATE_FILE_MODE) || !hasCurrentOwner (path))
                return null;

            final int fileLength = (int) attributes.size ();
            try (SeekableByteChannel channel = Files.newByteChannel (path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            {
                if (channel.size () != fileLength)
                    return null;
                final ByteBuffer destination = ByteBuffer.wrap (fileBytes, 0, fileLength);
                while (destination.hasRemaining ())
                {
                    final int count = channel.read (destination);
                    if (count < 0)
                        return null;
                }
                if (channel.size () != fileLength)
                    return null;
            }

            result = decodeToken (fileBytes, fileLength);
            return result;
        }
        catch (final IOException | RuntimeException ex)
        {
            if (result != null)
                Arrays.fill (result, (byte) 0);
            return null;
        }
        finally
        {
            Arrays.fill (fileBytes, (byte) 0);
        }
    }


    private static boolean hasCurrentOwner (final Path path) throws IOException
    {
        try
        {
            final String homePath = System.getProperty ("user.home");
            if (homePath == null || homePath.isBlank ())
                return false;
            final UserPrincipal tokenOwner = Files.getOwner (path, LinkOption.NOFOLLOW_LINKS);
            final UserPrincipal currentOwner = Files.getOwner (Path.of (homePath), LinkOption.NOFOLLOW_LINKS);
            return tokenOwner != null && tokenOwner.equals (currentOwner);
        }
        catch (final UnsupportedOperationException ex)
        {
            return true;
        }
    }


    private static byte [] decodeToken (final byte [] bytes, final int length)
    {
        final byte [] result = new byte [TOKEN_BYTES];
        for (int index = 0; index < TOKEN_BYTES; index++)
        {
            final int high = hexadecimalValue (bytes[index * 2]);
            final int low = hexadecimalValue (bytes[index * 2 + 1]);
            if (high < 0 || low < 0)
            {
                Arrays.fill (result, (byte) 0);
                return null;
            }
            result[index] = (byte) (high << 4 | low);
        }

        for (int index = TOKEN_HEX_BYTES; index < length; index++)
        {
            if (!isAsciiWhitespace (bytes[index]))
            {
                Arrays.fill (result, (byte) 0);
                return null;
            }
        }
        return result;
    }


    private static int hexadecimalValue (final byte value)
    {
        if (value >= '0' && value <= '9')
            return value - '0';
        if (value >= 'a' && value <= 'f')
            return value - 'a' + 10;
        if (value >= 'A' && value <= 'F')
            return value - 'A' + 10;
        return -1;
    }


    private static boolean isAsciiWhitespace (final byte value)
    {
        return value == ' ' || value >= '\t' && value <= '\r';
    }


    private static boolean constantTimeTokenEquals (final byte [] expected, final byte [] actual)
    {
        int difference = 0;
        for (int index = 0; index < TOKEN_BYTES; index++)
            difference |= expected[index] ^ actual[index];
        return difference == 0;
    }


    private static int getUnsignedShort (final byte [] bytes, final int offset)
    {
        return (bytes[offset] & 0xFF) << 8 | bytes[offset + 1] & 0xFF;
    }


    private static int getInt (final byte [] bytes, final int offset)
    {
        return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16 | (bytes[offset + 2] & 0xFF) << 8 | bytes[offset + 3] & 0xFF;
    }


    private static long getLong (final byte [] bytes, final int offset)
    {
        return (long) getInt (bytes, offset) << 32 | getInt (bytes, offset + 4) & 0xFFFFFFFFL;
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
            // Socket close is the bounded accept/read-unblock mechanism; there is no retry here.
        }
    }


    long getAcceptedFrames ()
    {
        return this.acceptedFrames;
    }


    long getAcceptedSessions ()
    {
        return this.acceptedSessions;
    }


    long getSequenceGapEvents ()
    {
        return this.sequenceGapEvents;
    }


    long getSequenceRejects ()
    {
        return this.sequenceRejects;
    }


    long getAuthenticationRejects ()
    {
        return this.authenticationRejects;
    }


    long getMalformedRejects ()
    {
        return this.malformedRejects;
    }


    long getTruncatedHeaders ()
    {
        return this.truncatedHeaders;
    }


    long getTruncatedPayloads ()
    {
        return this.truncatedPayloads;
    }


    long getDisconnects ()
    {
        return this.disconnects;
    }
}
