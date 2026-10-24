package org.stvnadore.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import javax.swing.Icon;
import java.util.HashMap;
import java.util.Map;

/**
 * Exposes customizable color options for STVN to the IntelliJ Color Settings page.
 */
@NullMarked
public final class StvnColorSettingsPage implements ColorSettingsPage {

    /**
     * Constructs a new StvnColorSettingsPage instance.
     */
    public StvnColorSettingsPage() {
    }

    private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[]{
            new AttributesDescriptor("Types//Primitive types (e.g., :Int, :Seq, :Option)", StvnSyntaxHighlighterColors.STVN_PRIMITIVE_TYPE),
            new AttributesDescriptor("Types//Nominal types (e.g., custom user schemas)", StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE),
            new AttributesDescriptor("Values//Value keywords (e.g., #TRUE, #Some, #None)", StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD),
            new AttributesDescriptor("Metadata//Metadata targets (e.g., #equatable, #regex)", StvnSyntaxHighlighterColors.STVN_METADATA_TARGET),
            new AttributesDescriptor("Comments//Line comment", DefaultLanguageHighlighterColors.LINE_COMMENT),
            new AttributesDescriptor("Strings//String literal", DefaultLanguageHighlighterColors.STRING),
            new AttributesDescriptor("Numbers//Number", DefaultLanguageHighlighterColors.NUMBER),
            new AttributesDescriptor("Brackets and Operators//Brackets", DefaultLanguageHighlighterColors.BRACKETS),
            new AttributesDescriptor("Brackets and Operators//Braces", DefaultLanguageHighlighterColors.BRACES),
            new AttributesDescriptor("Brackets and Operators//Parentheses", DefaultLanguageHighlighterColors.PARENTHESES)
    };

    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.Text;
    }

    @Override
    public SyntaxHighlighter getHighlighter() {
        return new StvnSyntaxHighlighter();
    }

    @Override
    public String getDemoText() {
        return "{\n" +
                "  <comment>// Canonical STVN 2.0.0 Specification Showcase Document</comment>\n" +
                "  <primitive>:meta</primitive> {\n" +
                "    <metadata>#name</metadata> <string>\"service_gateway\"</string>\n" +
                "    <metadata>#domain</metadata> <string>\"network\"</string>\n" +
                "    <metadata>#kind</metadata> <value>#BODY</value>\n" +
                "  }\n" +
                "  <primitive>:defs</primitive> {\n" +
                "    <primitive>:package</primitive> :org/example/service {\n" +
                "      <nominal>:Port</nominal> { <metadata>#unsigned</metadata> <metadata>#size</metadata> <number>16</number> <metadata>#minIncl</metadata> <number>1</number> } <primitive>:Int</primitive>\n" +
                "      <nominal>:State</nominal> <primitive>:Enum</primitive> [ <value>#ACTIVE</value> <value>#INACTIVE</value> <value>#SUSPENDED</value> ]\n" +
                "      <nominal>:ServiceConfig</nominal> <primitive>:Tuple</primitive>(\n" +
                "        <nominal>:Port</nominal>\n" +
                "        <nominal>:State</nominal>\n" +
                "        <primitive>:Option</primitive>(<primitive>:String</primitive>)\n" +
                "        <primitive>:Either</primitive>(<primitive>:String</primitive> <primitive>:Int</primitive>)\n" +
                "        <primitive>:Union</primitive>(<primitive>:Int</primitive> <primitive>:String</primitive> <primitive>:Boolean</primitive>)\n" +
                "        <primitive>:String</primitive>\n" +
                "        <primitive>:String</primitive>\n" +
                "      )\n" +
                "    }\n" +
                "  }\n" +
                "  <primitive>:type</primitive> :org/example/service/<nominal>ServiceConfig</nominal>\n" +
                "  <primitive>:body</primitive> (\n" +
                "    <number>8443</number>\n" +
                "    <value>#ACTIVE</value>\n" +
                "    <value>#Some</value> <string>\"Escaped string \\n \\t \\\"quotes\\\"\"</string>\n" +
                "    <value>#Left</value> <string>\"primary\"</string>\n" +
                "    <value>#2</value> <string>\"fallback\"</string>\n" +
                "    <string>\"Standard string literal\"</string>\n" +
                "    <string>\"\"\"[JSON]\n" +
                "{\n" +
                "  \"service\": \"active\",\n" +
                "  \"endpoint\": \"gateway.internal\"\n" +
                "}\n" +
                "[JSON]\"\"\"</string>\n" +
                "  )\n" +
                "}";
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        var map = new HashMap<String, TextAttributesKey>();
        map.put("primitive", StvnSyntaxHighlighterColors.STVN_PRIMITIVE_TYPE);
        map.put("nominal", StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE);
        map.put("value", StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD);
        map.put("metadata", StvnSyntaxHighlighterColors.STVN_METADATA_TARGET);
        map.put("comment", DefaultLanguageHighlighterColors.LINE_COMMENT);
        map.put("string", DefaultLanguageHighlighterColors.STRING);
        map.put("number", DefaultLanguageHighlighterColors.NUMBER);
        return map;
    }

    @Override
    public AttributesDescriptor[] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor[] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public String getDisplayName() {
        return "STVN";
    }
}
