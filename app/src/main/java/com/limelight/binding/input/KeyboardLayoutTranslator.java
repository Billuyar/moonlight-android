package com.limelight.binding.input;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.HashMap;
import java.util.Map;

/**
 * Translate IME-committed text into Moonlight VK keystrokes positioned for the
 * host's keyboard layout. Avoids the round-trip through a side-channel relay
 * daemon: the client already knows which scancode position produces 'ç' on a
 * Turkish Q host and sends that VK code directly through the normal protocol.
 *
 * Currently hardcoded for Turkish Q. To support other layouts add a layout
 * selector and parallel char→VK tables.
 */
public final class KeyboardLayoutTranslator {

    private KeyboardLayoutTranslator() {}

    // GFE wire format prefixes every VK with 0x80 in the high byte. Matches
    // KeyboardTranslator.KEY_PREFIX behaviour.
    private static final int KEY_PREFIX = 0x80 << 8;

    // Windows VK codes for positions that differ between US-QWERTY and Turkish Q.
    private static final short VK_LSHIFT       = 0xA0;
    private static final short VK_RMENU        = 0xA5; // Right Alt = AltGr on Linux
    private static final short VK_A            = 0x41;
    private static final short VK_I            = 0x49;
    private static final short VK_0            = 0x30;
    private static final short VK_SPACE        = 0x20;
    private static final short VK_RETURN       = 0x0D;
    private static final short VK_TAB          = 0x09;
    private static final short VK_OEM_1        = 0xBA; // ;:  → ş Ş on Turkish Q
    private static final short VK_OEM_PLUS     = 0xBB; // =+
    private static final short VK_OEM_COMMA    = 0xBC; // ,<  → ö Ö
    private static final short VK_OEM_MINUS    = 0xBD; // -_
    private static final short VK_OEM_PERIOD   = 0xBE; // .>  → ç Ç
    private static final short VK_OEM_2        = 0xBF; // /?  → . :
    private static final short VK_OEM_3        = 0xC0; // `~
    private static final short VK_OEM_4        = 0xDB; // [{  → ğ Ğ
    private static final short VK_OEM_5        = 0xDC; // \|  → , ;
    private static final short VK_OEM_6        = 0xDD; // ]}  → ü Ü
    private static final short VK_OEM_7        = 0xDE; // '"  → i İ

    private static final byte MOD_NONE  = 0;
    private static final byte MOD_SHIFT = KeyboardPacket.MODIFIER_SHIFT;

    private static final class Stroke {
        final short vk;
        final byte modifiers;
        final boolean altGr;
        Stroke(short vk, byte modifiers, boolean altGr) {
            this.vk = vk; this.modifiers = modifiers; this.altGr = altGr;
        }
        Stroke(short vk, byte modifiers) {
            this(vk, modifiers, false);
        }
    }

    private static final Map<Character, Stroke> TURKISH_Q = new HashMap<>();
    static {
        // Turkish-specific letters. Position notes refer to the equivalent
        // physical US-QWERTY key (so the host's keymap interprets them).
        TURKISH_Q.put('ı', new Stroke(VK_I,          MOD_NONE));   // I-position
        TURKISH_Q.put('I', new Stroke(VK_I,          MOD_SHIFT));
        TURKISH_Q.put('i', new Stroke(VK_OEM_7,      MOD_NONE));   // apostrophe
        TURKISH_Q.put('İ', new Stroke(VK_OEM_7,      MOD_SHIFT));
        TURKISH_Q.put('ğ', new Stroke(VK_OEM_4,      MOD_NONE));   // left bracket
        TURKISH_Q.put('Ğ', new Stroke(VK_OEM_4,      MOD_SHIFT));
        TURKISH_Q.put('ü', new Stroke(VK_OEM_6,      MOD_NONE));   // right bracket
        TURKISH_Q.put('Ü', new Stroke(VK_OEM_6,      MOD_SHIFT));
        TURKISH_Q.put('ş', new Stroke(VK_OEM_1,      MOD_NONE));   // semicolon
        TURKISH_Q.put('Ş', new Stroke(VK_OEM_1,      MOD_SHIFT));
        TURKISH_Q.put('ö', new Stroke(VK_OEM_COMMA,  MOD_NONE));   // comma
        TURKISH_Q.put('Ö', new Stroke(VK_OEM_COMMA,  MOD_SHIFT));
        TURKISH_Q.put('ç', new Stroke(VK_OEM_PERIOD, MOD_NONE));   // period
        TURKISH_Q.put('Ç', new Stroke(VK_OEM_PERIOD, MOD_SHIFT));

        // Punctuation positions that differ from US on Turkish Q.
        TURKISH_Q.put('.', new Stroke(VK_OEM_2,      MOD_NONE));   // slash position
        TURKISH_Q.put(':', new Stroke(VK_OEM_2,      MOD_SHIFT));
        TURKISH_Q.put(',', new Stroke(VK_OEM_5,      MOD_NONE));   // backslash position
        TURKISH_Q.put(';', new Stroke(VK_OEM_5,      MOD_SHIFT));

        // Top-row positions whose unshifted glyph isn't a digit on Turkish Q.
        TURKISH_Q.put('*', new Stroke(VK_OEM_MINUS,  MOD_NONE));   // US -_ position
        TURKISH_Q.put('-', new Stroke(VK_OEM_PLUS,   MOD_NONE));   // US =+ position
        TURKISH_Q.put('"', new Stroke(VK_OEM_3,      MOD_NONE));   // US `~ position

        // Top-row shifted symbols (Turkish Q layout).
        TURKISH_Q.put('!', new Stroke((short)0x31,   MOD_SHIFT));  // VK_1
        TURKISH_Q.put('\'',new Stroke((short)0x32,   MOD_SHIFT));  // VK_2
        TURKISH_Q.put('^', new Stroke((short)0x33,   MOD_SHIFT));  // VK_3
        TURKISH_Q.put('+', new Stroke((short)0x34,   MOD_SHIFT));  // VK_4
        TURKISH_Q.put('%', new Stroke((short)0x35,   MOD_SHIFT));  // VK_5
        TURKISH_Q.put('&', new Stroke((short)0x36,   MOD_SHIFT));  // VK_6
        TURKISH_Q.put('/', new Stroke((short)0x37,   MOD_SHIFT));  // VK_7
        TURKISH_Q.put('(', new Stroke((short)0x38,   MOD_SHIFT));  // VK_8
        TURKISH_Q.put(')', new Stroke((short)0x39,   MOD_SHIFT));  // VK_9
        TURKISH_Q.put('=', new Stroke((short)0x30,   MOD_SHIFT));  // VK_0
        TURKISH_Q.put('?', new Stroke(VK_OEM_MINUS,  MOD_SHIFT));
        TURKISH_Q.put('_', new Stroke(VK_OEM_PLUS,   MOD_SHIFT));

        // AltGr (level 3) symbols on Turkish Q.
        TURKISH_Q.put('>', new Stroke((short)0x31,   MOD_NONE,  true)); // AltGr+1
        TURKISH_Q.put('£', new Stroke((short)0x32,   MOD_NONE,  true)); // AltGr+2
        TURKISH_Q.put('#', new Stroke((short)0x33,   MOD_NONE,  true)); // AltGr+3
        TURKISH_Q.put('$', new Stroke((short)0x34,   MOD_NONE,  true)); // AltGr+4
        TURKISH_Q.put('{', new Stroke((short)0x37,   MOD_NONE,  true)); // AltGr+7
        TURKISH_Q.put('[', new Stroke((short)0x38,   MOD_NONE,  true)); // AltGr+8
        TURKISH_Q.put(']', new Stroke((short)0x39,   MOD_NONE,  true)); // AltGr+9
        TURKISH_Q.put('}', new Stroke((short)0x30,   MOD_NONE,  true)); // AltGr+0
        TURKISH_Q.put('\\',new Stroke(VK_OEM_MINUS,  MOD_NONE,  true)); // AltGr+*
        TURKISH_Q.put('|', new Stroke(VK_OEM_PLUS,   MOD_NONE,  true)); // AltGr+-
        TURKISH_Q.put('€', new Stroke((short)0x45,   MOD_NONE,  true)); // AltGr+E
        TURKISH_Q.put('~', new Stroke(VK_OEM_6,      MOD_NONE,  true)); // AltGr+ü
        TURKISH_Q.put('`', new Stroke(VK_OEM_5,      MOD_NONE,  true)); // AltGr+,
        TURKISH_Q.put('<', new Stroke(VK_OEM_3,      MOD_NONE,  true)); // AltGr+"
        TURKISH_Q.put('×', new Stroke(VK_OEM_COMMA,  MOD_NONE,  true)); // AltGr+ö (multiply)
        // Division ÷ is level 4 on Turkish Q (AltGr+Shift+ç).
        TURKISH_Q.put('÷', new Stroke(VK_OEM_PERIOD, MOD_SHIFT, true));
    }

    private static Stroke strokeForChar(char ch) {
        Stroke s = TURKISH_Q.get(ch);
        if (s != null) return s;

        // ASCII letters/digits whose Turkish Q position matches US-QWERTY.
        if (ch >= 'a' && ch <= 'z') return new Stroke((short)(VK_A + (ch - 'a')), MOD_NONE);
        if (ch >= 'A' && ch <= 'Z') return new Stroke((short)(VK_A + (ch - 'A')), MOD_SHIFT);
        if (ch >= '0' && ch <= '9') return new Stroke((short)(VK_0 + (ch - '0')), MOD_NONE);
        if (ch == ' ')  return new Stroke(VK_SPACE,  MOD_NONE);
        if (ch == '\n') return new Stroke(VK_RETURN, MOD_NONE);
        if (ch == '\t') return new Stroke(VK_TAB,    MOD_NONE);
        return null;
    }

    private static short gfe(short vk) {
        return (short)(KEY_PREFIX | (vk & 0xFF));
    }

    /**
     * Type the given string on the host as a sequence of VK keystrokes,
     * matching the host's Turkish Q keymap. Returns true if every character
     * was sent; false if any character was unsupported (those are skipped).
     */
    public static boolean sendAsKeystrokes(NvConnection conn, String text) {
        if (conn == null || text == null || text.isEmpty()) return true;
        boolean allMapped = true;
        final short shiftKey = gfe(VK_LSHIFT);
        final short rAltKey  = gfe(VK_RMENU);

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Stroke s = strokeForChar(ch);
            if (s == null) {
                allMapped = false;
                continue;
            }
            short key = gfe(s.vk);
            boolean shifted = (s.modifiers & MOD_SHIFT) != 0;
            byte mod = 0;

            if (s.altGr) {
                conn.sendKeyboardInput(rAltKey, KeyboardPacket.KEY_DOWN, mod, (byte)0);
                mod |= KeyboardPacket.MODIFIER_ALT;
            }
            if (shifted) {
                conn.sendKeyboardInput(shiftKey, KeyboardPacket.KEY_DOWN, mod, (byte)0);
                mod |= KeyboardPacket.MODIFIER_SHIFT;
            }
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, mod, (byte)0);
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_UP,   mod, (byte)0);
            if (shifted) {
                mod &= ~KeyboardPacket.MODIFIER_SHIFT;
                conn.sendKeyboardInput(shiftKey, KeyboardPacket.KEY_UP, mod, (byte)0);
            }
            if (s.altGr) {
                mod &= ~KeyboardPacket.MODIFIER_ALT;
                conn.sendKeyboardInput(rAltKey, KeyboardPacket.KEY_UP, mod, (byte)0);
            }
        }
        return allMapped;
    }
}
