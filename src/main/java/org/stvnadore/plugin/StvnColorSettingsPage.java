package org.stvnadore.plugin;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.intellij.icons.AllIcons;
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

    private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[]{
            new AttributesDescriptor("Primitive types (e.g., :Int, :Seq, :Option)", StvnSyntaxHighlighterColors.STVN_PRIMITIVE_TYPE),
            new AttributesDescriptor("Nominal types (e.g., custom user schemas)", StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE),
            new AttributesDescriptor("Value keywords (e.g., #TRUE, #Some, #None)", StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD),
            new AttributesDescriptor("Metadata targets (e.g., #equatable, #regex)", StvnSyntaxHighlighterColors.STVN_METADATA_TARGET)
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
                "  :defs {\n" +
                "    <nominal>:Uuid</nominal> { <metadata>#equatable</metadata> <value>#TRUE</value> } <primitive>:StringFixed32</primitive>\n" +
                "    <nominal>:Status</nominal> <primitive>:Enum</primitive> [ <value>#Left</value> <value>#Right</value> <value>#Pending</value> ]\n" +
                "  }\n" +
                "  :type <primitive>:Option</primitive>(<primitive>:Either</primitive>(<nominal>:Status</nominal> <primitive>:Int32</primitive>))\n" +
                "  :body <value>#Some</value> <value>#Right</value> 42\n" +
                "}";
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        var map = new HashMap<String, TextAttributesKey>();
        map.put("primitive", StvnSyntaxHighlighterColors.STVN_PRIMITIVE_TYPE);
        map.put("nominal", StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE);
        map.put("value", StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD);
        map.put("metadata", StvnSyntaxHighlighterColors.STVN_METADATA_TARGET);
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
