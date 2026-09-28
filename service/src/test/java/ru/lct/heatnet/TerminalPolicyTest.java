package ru.lct.heatnet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.routing.TerminalPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerminalPolicyTest {

    @AfterEach
    void clear() {
        System.clearProperty("heatnet.terminal-policy");
    }

    @Test
    void defaultIsLiteralAndValuesParse() {
        System.clearProperty("heatnet.terminal-policy");
        if (System.getenv("HEATNET_TERMINAL_POLICY") == null) {
            assertEquals(TerminalPolicy.LITERAL, TerminalPolicy.current());
        }
        System.setProperty("heatnet.terminal-policy", "literal");
        assertEquals(TerminalPolicy.LITERAL, TerminalPolicy.current());
        System.setProperty("heatnet.terminal-policy", " Exterior ");
        assertEquals(TerminalPolicy.EXTERIOR, TerminalPolicy.current());
        assertEquals("exterior", TerminalPolicy.current().code());
        System.setProperty("heatnet.terminal-policy", "strict");
        assertEquals(TerminalPolicy.LITERAL, TerminalPolicy.current());
        System.setProperty("heatnet.terminal-policy", "any");
        assertEquals(TerminalPolicy.RELAXED, TerminalPolicy.current());
        System.setProperty("heatnet.terminal-policy", "whatever");
        assertThrows(IllegalArgumentException.class, TerminalPolicy::current);
    }
}

