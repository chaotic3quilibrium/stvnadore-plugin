package org.stvnadore.psi;

import com.intellij.psi.tree.IElementType;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Base token type for the STVN JFlex lexer.
 */
@NullMarked
public class StvnTokenType extends IElementType {
    
    /**
     * Constructs a token type with a debug name associated with the STVN language.
     *
     * @param debugName the name of the token type for debugging
     */
    public StvnTokenType(String debugName) {
        super(debugName, StvnLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "StvnTokenType." + super.toString();
    }
}
