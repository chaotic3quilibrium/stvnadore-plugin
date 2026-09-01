package org.stvnadore.plugin;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.FlexAdapter;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.parser.StvnParser;
import org.stvnadore.parser._StvnLexer;
import org.stvnadore.psi.StvnTypes;

/**
 * Core ParserDefinition class connecting JFlex and Grammar-Kit outputs to the IntelliJ Platform.
 */
@NullMarked
public final class StvnParserDefinition implements ParserDefinition {
    /** File node element type for STVN document root. */
    public static final IFileElementType FILE = new IFileElementType("STVN_FILE", StvnLanguage.INSTANCE);

    /** Constructs a new StvnParserDefinition. */
    public StvnParserDefinition() {}

    @Override
    public Lexer createLexer(Project project) {
        return new FlexAdapter(new _StvnLexer(null));
    }

    @Override
    public PsiParser createParser(Project project) {
        return new StvnParser();
    }

    @Override
    public IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public TokenSet getCommentTokens() {
        return TokenSet.create(StvnTypes.COMMENT);
    }

    @Override
    public TokenSet getStringLiteralElements() {
        return TokenSet.create(
            StvnTypes.LITERAL_STRING_SIMPLE,
            StvnTypes.LITERAL_STRING_BLOCK,
            StvnTypes.LITERAL_STRING_FENCED
        );
    }

    @Override
    public PsiElement createElement(ASTNode node) {
        return StvnTypes.Factory.createElement(node);
    }

    @Override
    public PsiFile createFile(FileViewProvider viewProvider) {
        return new StvnPayloadFile(viewProvider);
    }
}