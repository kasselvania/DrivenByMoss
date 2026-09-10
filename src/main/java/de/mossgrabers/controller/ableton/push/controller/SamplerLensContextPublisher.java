// Pushwig Sampler context handoff (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.daw.IHost;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.FileAlreadyExistsException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;


/** One coalesced non-secret context notice. No frames, input binding work, or I/O on display send. */
final class SamplerLensContextPublisher implements AutoCloseable, Runnable
{
    private record State (UUID session) { }

    private final IHost host;
    private final Path root;
    private final Path ingressRoot;
    private final Path current;
    private final Path temporary;
    private final String ingressGeneration;
    private final AtomicReference<State> offered;
    private final Thread worker;
    private volatile boolean closing;
    private volatile boolean failed;
    private volatile boolean published;


    SamplerLensContextPublisher (final IHost host, final PushwigRuntimeRendezvous rendezvous, final UUID initial)
    {
        this.host = host;
        this.ingressRoot = rendezvous.getRuntimeRoot ();
        this.root = this.ingressRoot.resolveSibling ("sampler-lens-v1");
        this.ingressGeneration = rendezvous.getGeneration ();
        // The accepted ingress manifest selects this generation's notice. An old shutdown must
        // never remove a new controller session's file, even if their lifetimes briefly overlap.
        this.current = this.root.resolve (this.ingressGeneration + ".json");
        this.temporary = this.root.resolve (this.ingressGeneration + ".tmp");
        this.offered = new AtomicReference<> (new State (initial));
        this.worker = new Thread (this, "Pushwig Sampler Context Notice");
        this.worker.setDaemon (true);
        this.worker.start ();
    }


    void offer (final UUID session)
    {
        final State previous = this.offered.get ();
        if (!java.util.Objects.equals (previous.session (), session))
            this.offered.set (new State (session));
    }


    boolean isAvailable ()
    {
        return this.published && !this.failed && !this.closing;
    }


    @Override
    public void run ()
    {
        State written = null;
        try
        {
            try
            {
                Files.createDirectory (this.root, PosixFilePermissions.asFileAttribute (PosixFilePermissions.fromString ("rwx------")));
            }
            catch (final FileAlreadyExistsException ex)
            {
                this.validateRoot ();
            }
            while (!this.closing)
            {
                final State state = this.offered.get ();
                if (state != written)
                {
                    if (this.publish (state))
                    {
                        written = state;
                        this.published = true;
                    }
                }
                Thread.sleep (100);
            }
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread ().interrupt ();
        }
        catch (final IOException | RuntimeException ex)
        {
            this.failed = true;
            this.host.error ("Pushwig Sampler context notice unavailable; retaining semantics.");
        }
        finally
        {
            this.published = false;
            try
            {
                this.validateRoot ();
                if (Files.exists (this.temporary, LinkOption.NOFOLLOW_LINKS))
                {
                    this.validateFile (this.temporary);
                    Files.delete (this.temporary);
                }
                if (written != null && Files.exists (this.current, LinkOption.NOFOLLOW_LINKS))
                {
                    this.validateFile (this.current);
                    Files.delete (this.current);
                }
            }
            catch (final IOException ex)
            {
                this.host.error ("Pushwig Sampler context notice cleanup refused an unsafe or missing path.");
            }
        }
    }


    private boolean publish (final State state) throws IOException
    {
        this.validateRoot ();
        if (Files.exists (this.current, LinkOption.NOFOLLOW_LINKS))
            this.validateFile (this.current);
        if (Files.exists (this.temporary, LinkOption.NOFOLLOW_LINKS))
        {
            this.validateFile (this.temporary);
            Files.delete (this.temporary);
        }
        final String context = state.session () == null ? "null" : "\"" + state.session ().toString ().replace ("-", "") + "\"";
        final String json = "{\"schema_version\":1,\"ingress_generation\":\"" + this.ingressGeneration +
            "\",\"native_device\":\"bitwig-sampler\",\"context_session\":" + context +
            ",\"destination\":[238,25,484,114]}\n";
        Files.createFile (this.temporary, PosixFilePermissions.asFileAttribute (PosixFilePermissions.fromString ("rw-------")));
        Files.writeString (this.temporary, json, StandardCharsets.UTF_8, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        if (this.closing)
            return false;
        Files.move (this.temporary, this.current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }


    private void validateRoot () throws IOException
    {
        final PosixFileAttributes attributes = Files.readAttributes (this.root, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory () || !attributes.permissions ().equals (PosixFilePermissions.fromString ("rwx------")) ||
            !attributes.owner ().equals (Files.getOwner (this.ingressRoot, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException ("Unsafe runtime directory.");
    }


    private void validateFile (final Path path) throws IOException
    {
        final PosixFileAttributes attributes = Files.readAttributes (path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile () || !attributes.permissions ().equals (PosixFilePermissions.fromString ("rw-------")) ||
            !attributes.owner ().equals (Files.getOwner (this.root, LinkOption.NOFOLLOW_LINKS)) || attributes.size () > 1024)
            throw new IOException ("Unsafe context notice.");
    }


    @Override
    public void close ()
    {
        this.closing = true;
        this.worker.interrupt ();
        try
        {
            this.worker.join (500);
            if (this.worker.isAlive ())
                this.host.error ("Pushwig context notice worker exceeded bounded shutdown; visual authority is revoked.");
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread ().interrupt ();
        }
    }
}
