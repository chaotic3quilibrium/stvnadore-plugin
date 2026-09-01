package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.psi.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility for extracting sanitized, comment-free, canonically formatted schema representations
 * from both IntelliJ PSI trees and ANTLR4 parse trees.
 */
@NullMarked
public final class StvnSchemaFormatter {

    private static final Pattern COMMENT_PATTERN = Pattern.compile("//[^\\r\\n]*");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private StvnSchemaFormatter() {}

    /**
     * Extracts a clean, canonically formatted schema type string from a PSI element,
     * enforcing standard whitespace padding around delimiters and stripping comments.
     *
     * @param element the PSI element (typically SchemaType, SchemaConstructor, etc.)
     * @return the cleanly formatted canonical schema signature, or an empty string if null
     */
    public static @NotNull String formatCleanSchema(@Nullable PsiElement element) {
        if (element == null) {
            return "";
        }
        if (element instanceof SchemaType schemaType) {
            return formatPsiSchemaType(schemaType);
        }
        if (element instanceof SchemaConstructor ctor) {
            return formatPsiSchemaConstructor(ctor);
        }
        return formatFallbackPsi(element);
    }

    private static String formatPsiSchemaType(SchemaType schemaType) {
        var keyword = schemaType.getTypeKeyword();
        if (keyword != null) {
            return keyword.getText().trim();
        }
        var ctor = schemaType.getSchemaConstructor();
        if (ctor != null) {
            return formatPsiSchemaConstructor(ctor);
        }
        return formatFallbackPsi(schemaType);
    }

    private static String formatPsiSchemaConstructor(SchemaConstructor ctor) {
        var atomic = ctor.getAtomicType();
        if (atomic != null) {
            return atomic.getText().trim();
        }
        var sum = ctor.getSumType();
        if (sum != null) {
            if (sum.getNode().findChildByType(StvnTypes.KW_OPTION) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return ":Option( " + formatPsiChildren(children) + " )";
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_EITHER) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return ":Either( " + formatPsiChildren(children) + " )";
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_UNION) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return ":Union( " + formatPsiChildren(children) + " )";
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_ENUM) != null) {
                var enumDef = sum.getEnumDef();
                if (enumDef != null) {
                    var kws = enumDef.getValueKeywordList();
                    var list = new ArrayList<String>();
                    for (var kw : kws) {
                        list.add(kw.getText().trim());
                    }
                    return ":Enum [ " + String.join(" ", list) + " ]";
                }
            }
        }
        var prod = ctor.getProductType();
        if (prod != null) {
            var children = prod.getSchemaTypeList();
            return ":Tuple( " + formatPsiChildren(children) + " )";
        }
        var coll = ctor.getCollectionType();
        if (coll != null) {
            var first = coll.getFirstChild();
            var collName = first != null ? first.getText().trim() : ":Seq";
            var children = coll.getSchemaTypeList();
            return collName + "( " + formatPsiChildren(children) + " )";
        }
        return formatFallbackPsi(ctor);
    }

    private static String formatPsiChildren(List<SchemaType> children) {
        var list = new ArrayList<String>();
        for (var child : children) {
            list.add(formatPsiSchemaType(child));
        }
        return String.join(" ", list);
    }

    /**
     * Extracts a clean, canonically formatted schema type string from an ANTLR parse tree,
     * enforcing standard whitespace padding around delimiters and stripping comments.
     *
     * @param tree the ANTLR parse tree (typically SchemaTypeContext or ParserRuleContext)
     * @return the cleanly formatted canonical schema signature, or an empty string if null
     */
    public static @NotNull String formatCleanAntlrSchema(@Nullable ParseTree tree) {
        if (tree == null) {
            return "";
        }
        if (tree instanceof StvnParser.SchemaTypeContext schemaCtx) {
            return formatAntlrSchemaType(schemaCtx);
        }
        if (tree instanceof StvnParser.SchemaConstructorContext ctorCtx) {
            return formatAntlrSchemaConstructor(ctorCtx);
        }
        return formatFallbackAntlr(tree);
    }

    private static String formatAntlrSchemaType(StvnParser.SchemaTypeContext ctx) {
        if (ctx.typeKeyword() != null) {
            return ctx.typeKeyword().getText().trim();
        }
        if (ctx.schemaConstructor() != null) {
            return formatAntlrSchemaConstructor(ctx.schemaConstructor());
        }
        return formatFallbackAntlr(ctx);
    }

    private static String formatAntlrSchemaConstructor(StvnParser.SchemaConstructorContext ctx) {
        if (ctx.atomicType() != null) {
            return ctx.atomicType().getText().trim();
        }
        if (ctx.sumType() != null) {
            var sum = ctx.sumType();
            if (sum.KW_OPTION() != null) {
                return ":Option( " + formatAntlrChildren(sum.schemaType()) + " )";
            }
            if (sum.KW_EITHER() != null) {
                return ":Either( " + formatAntlrChildren(sum.schemaType()) + " )";
            }
            if (sum.KW_UNION() != null) {
                return ":Union( " + formatAntlrChildren(sum.schemaType()) + " )";
            }
            if (sum.KW_ENUM() != null && sum.enumDef() != null) {
                var list = new ArrayList<String>();
                for (var kw : sum.enumDef().valueKeyword()) {
                    list.add(kw.getText().trim());
                }
                return ":Enum [ " + String.join(" ", list) + " ]";
            }
        }
        if (ctx.productType() != null) {
            if (ctx.productType() instanceof StvnParser.TupleTypeContext tupleCtx) {
                return ":Tuple( " + formatAntlrChildren(tupleCtx.schemaType()) + " )";
            }
            return ":Tuple";
        }
        if (ctx.collectionType() != null) {
            var coll = ctx.collectionType();
            var collName = coll.getChild(0).getText().trim();
            return collName + "( " + formatAntlrChildren(coll.schemaType()) + " )";
        }
        return formatFallbackAntlr(ctx);
    }

    private static String formatAntlrChildren(List<StvnParser.SchemaTypeContext> children) {
        var list = new ArrayList<String>();
        for (var child : children) {
            list.add(formatAntlrSchemaType(child));
        }
        return String.join(" ", list);
    }

    private static String formatFallbackPsi(PsiElement element) {
        var raw = element.getText();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        var stripped = COMMENT_PATTERN.matcher(raw).replaceAll("");
        return WHITESPACE_PATTERN.matcher(stripped).replaceAll(" ").trim();
    }

    private static String formatFallbackAntlr(ParseTree tree) {
        if (tree instanceof ParserRuleContext ctx && ctx.start != null && ctx.stop != null) {
            var charStream = ctx.start.getInputStream();
            if (charStream != null) {
                var interval = new org.antlr.v4.runtime.misc.Interval(ctx.start.getStartIndex(), ctx.stop.getStopIndex());
                var raw = charStream.getText(interval);
                var stripped = COMMENT_PATTERN.matcher(raw).replaceAll("");
                return WHITESPACE_PATTERN.matcher(stripped).replaceAll(" ").trim();
            }
        }
        var raw = tree.getText();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        var stripped = COMMENT_PATTERN.matcher(raw).replaceAll("");
        return WHITESPACE_PATTERN.matcher(stripped).replaceAll(" ").trim();
    }
}
