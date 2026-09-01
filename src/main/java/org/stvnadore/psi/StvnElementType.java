package org.stvnadore.psi;

import com.intellij.psi.tree.IElementType;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Base PSI element type for the STVN parser.
 */
@NullMarked
public class StvnElementType extends IElementType {
    
    /**
     * Constructs an element type with a debug name associated with the STVN language.
     *
     * @param debugName the name of the element type for debugging
     */
    public StvnElementType(String debugName) {
        super(debugName, StvnLanguage.INSTANCE);
    }
}
