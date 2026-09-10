// Pushwig contextual Sampler presentation (c) 2026 Peter Kassel
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.ableton.push.controller;

import de.mossgrabers.framework.controller.color.ColorEx;
import de.mossgrabers.framework.graphics.Align;
import de.mossgrabers.framework.graphics.IGraphicsContext;
import de.mossgrabers.framework.graphics.IRenderer;

import java.util.List;
import java.util.Objects;


/**
 * Approved layout A. Immutable controller readouts around a producer-owned center image.
 * This renderer never claims native identity, assignment identity or a remote-to-image marker.
 */
public final class SamplerLensPresentation implements IRenderer
{
    public static final int IMAGE_X = 238;
    public static final int IMAGE_Y = 25;
    public static final int IMAGE_WIDTH = 484;
    public static final int IMAGE_HEIGHT = 114;

    private static final ColorEx BACKGROUND = ColorEx.fromRGB (16, 19, 24);
    private static final ColorEx PANEL = ColorEx.fromRGB (25, 30, 37);
    private static final ColorEx TOUCHED = ColorEx.fromRGB (41, 50, 61);
    private static final ColorEx TEXT = ColorEx.fromRGB (241, 243, 245);
    private static final ColorEx MUTED = ColorEx.fromRGB (138, 150, 163);
    private static final ColorEx [] SLOT_COLORS = {
        ColorEx.fromRGB (243, 25, 54), ColorEx.fromRGB (255, 116, 22),
        ColorEx.fromRGB (239, 213, 43), ColorEx.fromRGB (124, 219, 0),
        ColorEx.fromRGB (90, 199, 135), ColorEx.fromRGB (81, 158, 236),
        ColorEx.fromRGB (187, 99, 255), ColorEx.fromRGB (255, 74, 167)
    };
    private static final String [] SLOT_NUMBERS = { "1", "2", "3", "4", "5", "6", "7", "8" };

    /** A physical column's action/navigation plus its encoder's independent remote readout. */
    public record Slot (String action, boolean actionActive, String navigation, boolean navigationActive,
                        ColorEx navigationColor, String alias, String value, boolean exists, boolean touched)
    {
        public Slot
        {
            Objects.requireNonNull (action);
            Objects.requireNonNull (navigation);
            Objects.requireNonNull (navigationColor);
            Objects.requireNonNull (alias);
            Objects.requireNonNull (value);
            // Keep strings bounded before rendering. Clipping is still performed by the backend.
            if (action.length () > 32 || navigation.length () > 64 || alias.length () > 64 || value.length () > 64)
                throw new IllegalArgumentException ("Unbounded Sampler readout.");
        }
    }

    private final List<Slot> slots;


    public SamplerLensPresentation (final List<Slot> slots)
    {
        if (slots.size () != 8)
            throw new IllegalArgumentException ("A Push presentation requires eight physical slots.");
        this.slots = List.copyOf (slots);
    }


    public List<Slot> getSlots ()
    {
        return this.slots;
    }


    @Override
    public void render (final IGraphicsContext gc)
    {
        // Clear only outside the exact center payload. No pixel copy, output bitmap or image zoom.
        gc.fillRectangle (0, 0, 960, IMAGE_Y, BACKGROUND);
        gc.fillRectangle (0, IMAGE_Y + IMAGE_HEIGHT, 960, 160 - IMAGE_Y - IMAGE_HEIGHT, BACKGROUND);
        gc.fillRectangle (0, IMAGE_Y, IMAGE_X, IMAGE_HEIGHT, BACKGROUND);
        gc.fillRectangle (IMAGE_X + IMAGE_WIDTH, IMAGE_Y, 960 - IMAGE_X - IMAGE_WIDTH, IMAGE_HEIGHT, BACKGROUND);

        for (int index = 0; index < 8; index++)
        {
            final Slot slot = this.slots.get (index);
            final int columnX = index * 120;
            final ColorEx color = slot.exists () ? SLOT_COLORS[index] : MUTED;

            gc.drawTextInBounds (slot.action (), columnX + 3, 0, 114, 20, Align.CENTER, slot.actionActive () ? TEXT : MUTED, 11);
            if (slot.actionActive ())
                gc.fillRectangle (columnX + 6, 20, 108, 2, TEXT);

            if (slot.navigationActive ())
                gc.fillRectangle (columnX + 2, 143, 116, 17, slot.navigationColor ());
            gc.drawTextInBounds (slot.navigation (), columnX + 5, 142, 110, 18, Align.CENTER, slot.navigationActive () ? ColorEx.BLACK : MUTED, 11);

            final int x = index < 4 ? 0 : 730;
            final int y = 25 + index % 4 * 28;
            gc.fillRectangle (x, y, 230, 27, slot.touched () && slot.exists () ? TOUCHED : PANEL);
            gc.fillRectangle (x, y, 3, 27, color);
            gc.drawTextInBounds (SLOT_NUMBERS[index], x + 7, y, 18, 27, Align.CENTER, color, 13);
            gc.drawTextInBounds (slot.exists () ? slot.alias () : "Unassigned", x + 31, y, 192, 12, Align.LEFT, color, 11);
            gc.drawTextInBounds (slot.exists () ? slot.value () : "", x + 31, y + 11, 192, 16, Align.LEFT, TEXT, 15);
        }
    }
}
