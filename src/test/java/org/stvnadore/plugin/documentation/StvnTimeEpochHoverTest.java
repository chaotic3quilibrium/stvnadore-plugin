package org.stvnadore.plugin.documentation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnSettings;

/**
 * Platform integration test suite for TimeEpoch hover documentation cards,
 * 12h vs 24h format selection, and amber warning banner presentation.
 */
@NullMarked
public final class StvnTimeEpochHoverTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider provider = new StvnDocumentationProvider();

    private String getDocAtOffset(String text, int offset) {
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull("PSI element at offset " + offset + " must not be null", elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull("Documentation element at offset " + offset + " must not be null", docElem);
        var doc = provider.generateDoc(docElem, elem);
        assertNotNull("Generated documentation at offset " + offset + " must not be null", doc);
        return doc;
    }

    public void testHoverCardSecondsHour12() {
        var text = """
            {
              :type { #s } :TimeEpoch
              :body 1704067200
            }
            """;
        myFixture.configureByText("epoch_s_12.stvn", text);
        StvnSettings.getInstance(getProject()).getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_12;
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;

        var offset = text.indexOf("1704067200");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Expected Value Type: { #s } :TimeEpoch", doc.contains("<b>Value Type:</b> { #s } :TimeEpoch"));
        assertTrue("Expected Expression: 1704067200", doc.contains("<b>Expression:</b> 1704067200"));
        assertTrue("Expected 12-hour UTC timestamp", doc.contains("Monday, January 1, 2024, at 12:00:00 AM UTC"));
        assertFalse("Clean timestamp must NOT contain warning banner", doc.contains("Suspicious Epoch Magnitude Warning"));
    }

    public void testHoverCardMillisecondsHour24() {
        var text = """
            {
              :type { #ms } :TimeEpoch
              :body 1789241400006
            }
            """;
        myFixture.configureByText("epoch_ms_24.stvn", text);
        StvnSettings.getInstance(getProject()).getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_24;
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;

        var offset = text.indexOf("1789241400006");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Expected 24-hour UTC timestamp with milliseconds",
            doc.contains("Saturday, September 12, 2026, at 19:30:00.006 UTC"));
        assertFalse("Clean timestamp must NOT contain warning banner", doc.contains("Suspicious Epoch Magnitude Warning"));
    }

    public void testHoverCardMagnitudeWarningPresenceWhenEnabled() {
        var text = """
            {
              :type { #s } :TimeEpoch
              :body 1704067200000
            }
            """;
        myFixture.configureByText("epoch_warn_enabled.stvn", text);
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;

        var offset = text.indexOf("1704067200000");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Expected amber warning callout banner", doc.contains("Suspicious Epoch Magnitude Warning"));
        assertTrue("Expected year outside civil window indication", doc.contains("falls outside civil window [1900, 2100]"));
        assertTrue("Expected scale mismatch mention", doc.contains("suggesting #ms"));
    }

    public void testHoverCardMagnitudeWarningSuppressionWhenDisabled() {
        var text = """
            {
              :type { #s } :TimeEpoch
              :body 1704067200000
            }
            """;
        myFixture.configureByText("epoch_warn_disabled.stvn", text);
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = false;

        var offset = text.indexOf("1704067200000");
        var doc = getDocAtOffset(text, offset);

        assertFalse("Warning banner must be suppressed when showEpochMagnitudeWarnings is false",
            doc.contains("Suspicious Epoch Magnitude Warning"));
        assertTrue("UTC time must still render", doc.contains("<b>UTC Time:</b>"));
    }

    public void testHoverCardAstronomicalBoundsAndWarning() {
        var text = """
            {
              :type { #s } :TimeEpoch
              :body 1789241400000000006
            }
            """;
        myFixture.configureByText("astronomical.stvn", text);
        StvnSettings.getInstance(getProject()).getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_24;
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;

        var offset = text.indexOf("1789241400000000006");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Expected qualified Value Type: { #s } :TimeEpoch", doc.contains("<b>Value Type:</b> { #s } :TimeEpoch"));
        assertTrue("Expected Astronomical fallback without exception", doc.contains("<b>UTC Time:</b> Astronomical (> 1 billion years)"));
        assertTrue("Expected amber magnitude warning", doc.contains("Suspicious Epoch Magnitude Warning"));
        assertTrue("Expected suggestion for #ns", doc.contains("suggesting #ns"));
    }

    public void testNominalEpochAliasHoverCard() {
        var text = """
            {
              :defs {
                :CreatedTime { #ns } :TimeEpoch
              }
              :type :CreatedTime
              :body 1704067200000000000
            }
            """;
        myFixture.configureByText("epoch_alias.stvn", text);
        var offset = text.indexOf("1704067200000000000");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Expected nominal alias with qualified terminal trace",
            doc.contains("<b>Value Type:</b> :CreatedTime (-> { #ns } :TimeEpoch)"));
    }
}
