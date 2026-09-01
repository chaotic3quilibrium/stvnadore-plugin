package org.stvnadore.plugin.reference;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves STVN TypeKeyword PSI elements to their target TypeDefinition declarations.
 */
@NullMarked
public final class StvnTypeReference extends PsiReferenceBase<TypeKeyword> {

    private static final class Candidate {
        final PsiElement element;
        final boolean isLhsRename;

        Candidate(PsiElement element, boolean isLhsRename) {
            this.element = element;
            this.isLhsRename = isLhsRename;
        }
    }

    /**
     * Constructs an StvnTypeReference for the given TypeKeyword element.
     *
     * @param element the TypeKeyword element
     */
    public StvnTypeReference(TypeKeyword element) {
        super(element, new TextRange(0, element.getTextLength()));
    }

    @Override
    public @Nullable PsiElement resolve() {
        var keyword = getElement();
        var parent = keyword.getParent();

        // 1. Declarations should not resolve to anything else
        if (parent instanceof TypeDefinition typeDef) {
            if (typeDef.getTypeKeyword() == keyword) {
                return null;
            }
        }

        var isAliasFirstKeyword = false;
        if (parent instanceof IncludeMapAlias alias) {
            var list = alias.getTypeKeywordList();
            // Defensive typing accessors: verify child token count
            if (list.size() >= 2) {
                if (list.get(0) == keyword) {
                    isAliasFirstKeyword = true;
                } else {
                    // Second keyword (alias definition name) does not resolve
                    return null;
                }
            } else {
                return null;
            }
        }

        var containingFile = keyword.getContainingFile();
        if (containingFile == null) {
            return null;
        }

        var targetTypeName = keyword.getText();
        var visited = new HashSet<PsiFile>();

        if (isAliasFirstKeyword) {
            // First keyword in mapping. Resolve to the remote type in the included file.
            var includeElement = PsiTreeUtil.getParentOfType(parent, IncludeElement.class);
            if (includeElement != null) {
                var stringLit = includeElement.getStringLiteral();
                if (stringLit != null) {
                    var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                    if (targetFile != null) {
                        return StvnTypeReference.resolveTypeInFile(targetFile, targetTypeName, visited);
                    }
                }
            }
            return null;
        }

        // Regular type reference lookup: check local then remote
        return StvnTypeReference.resolveTypeInFile(containingFile, targetTypeName, visited);
    }

    public static @Nullable PsiElement resolveTypeInFile(PsiFile file, String targetName, Set<PsiFile> visited) {
        return resolveTypeInFile(file, targetName, visited, null);
    }

    public static @Nullable PsiElement resolveTypeInFile(PsiFile file, String targetName, Set<PsiFile> visited, @Nullable List<String> trace) {
        if (!visited.add(file)) {
            return null;
        }

        // 1. Local definitions scanning
        var definitions = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : definitions) {
            var keyword = def.getTypeKeyword();
            if (keyword != null && keyword.getText().equals(targetName)) {
                return keyword;
            }
        }

        // 2. Local include alias map scanning (first leg of multi-hop)
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var aliasBlock = incl.getIncludeAliasBlock();
            if (aliasBlock != null) {
                var aliases = PsiTreeUtil.findChildrenOfType(aliasBlock, IncludeMapAlias.class);
                for (var alias : aliases) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var localKw = list.get(1);
                        if (localKw != null && localKw.getText().equals(targetName)) {
                            if (trace != null) {
                                var remoteKw = list.get(0);
                                if (remoteKw != null) {
                                    trace.add(remoteKw.getText());
                                }
                            }
                            return localKw;
                        }
                    }
                }
            }
        }

        // 3. Ingestion looping that handles additive un-aliased fallbacks
        var candidates = new ArrayList<Candidate>();
        for (var incl : includes) {
            var stringLit = incl.getStringLiteral();
            if (stringLit == null) {
                continue;
            }
            var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
            if (targetFile == null) {
                continue;
            }

            var aliasBlock = incl.getIncludeAliasBlock();
            if (aliasBlock != null) {
                var aliases = PsiTreeUtil.findChildrenOfType(aliasBlock, IncludeMapAlias.class);
                var rhsMatched = false;
                var matchedAlias = (IncludeMapAlias) null;

                for (var alias : aliases) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var remoteKw = list.get(0);
                        var localKw = list.get(1);
                        if (localKw != null && remoteKw != null && localKw.getText().equals(targetName)) {
                            rhsMatched = true;
                            matchedAlias = alias;
                            break;
                        }
                    }
                }

                if (rhsMatched && matchedAlias != null) {
                    var remoteKw = matchedAlias.getTypeKeywordList().get(0);
                    var resolved = StvnTypeReference.resolveTypeInFile(targetFile, remoteKw.getText(), visited, trace);
                    if (resolved != null) {
                        candidates.add(new Candidate(resolved, false));
                    }
                } else {
                    // Fallthrough to search target file for the un-aliased raw name
                    var resolved = StvnTypeReference.resolveTypeInFile(targetFile, targetName, visited, trace);
                    if (resolved != null) {
                        var isLhsRename = false;
                        for (var alias : aliases) {
                            var list = alias.getTypeKeywordList();
                            if (list.size() >= 2) {
                                var remoteKw = list.get(0);
                                if (remoteKw != null && remoteKw.getText().equals(targetName)) {
                                    isLhsRename = true;
                                    break;
                                }
                            }
                        }
                        candidates.add(new Candidate(resolved, isLhsRename));
                    }
                }
            } else {
                // No alias block: search target file for raw name
                var resolved = StvnTypeReference.resolveTypeInFile(targetFile, targetName, visited, trace);
                if (resolved != null) {
                    candidates.add(new Candidate(resolved, false));
                }
            }
        }

        // 4. Candidate Eviction Cascades
        var rawOrRhs = new ArrayList<Candidate>();
        var lhsRenamed = new ArrayList<Candidate>();
        for (var c : candidates) {
            if (c.isLhsRename) {
                lhsRenamed.add(c);
            } else {
                rawOrRhs.add(c);
            }
        }

        if (!lhsRenamed.isEmpty() && !rawOrRhs.isEmpty()) {
            lhsRenamed.clear(); // Asymmetric Ingestion Eviction
        } else if (lhsRenamed.size() > 1) {
            lhsRenamed.clear(); // Dual Ingestion Eviction
        }

        var remaining = new ArrayList<Candidate>();
        remaining.addAll(rawOrRhs);
        remaining.addAll(lhsRenamed);

        if (remaining.size() == 1) {
            return remaining.get(0).element;
        }

        return null;
    }

    public static java.util.List<String> extractResolutionTrace(TypeKeyword startKeyword) {
        var trace = new java.util.ArrayList<String>();
        trace.add(startKeyword.getText());
        var visitedFiles = new java.util.HashSet<PsiFile>();
        var currentElement = resolveTypeInFile(startKeyword.getContainingFile(), startKeyword.getText(), visitedFiles, trace);
        
        var visitedElements = new java.util.HashSet<PsiElement>();
        if (currentElement != null) {
            visitedElements.add(currentElement);
        }

        while (currentElement != null) {
            var parent = currentElement.getParent();
            if (parent instanceof TypeDefinition typeDef) {
                var schemaType = typeDef.getSchemaType();
                if (schemaType != null) {
                    var nextKeyword = schemaType.getTypeKeyword();
                    var nextTypeName = "";
                    if (nextKeyword != null) {
                        nextTypeName = nextKeyword.getText().trim();
                    } else if (schemaType.getSchemaConstructor() != null) {
                        var ctor = schemaType.getSchemaConstructor();
                        if (ctor.getAtomicType() != null) {
                            nextTypeName = org.stvnadore.plugin.psi.StvnSchemaFormatter.formatCleanSchema(schemaType);
                        } else if (ctor.getSumType() != null && ctor.getSumType().getEnumDef() != null) {
                            nextTypeName = ":Enum";
                        }
                    }

                    if (!nextTypeName.isEmpty()) {
                        if (isPrimitiveTypeName(nextTypeName) || nextTypeName.equals(":Enum")) {
                            trace.add(nextTypeName);
                            break;
                        }

                        if (nextKeyword != null) {
                            trace.add(nextTypeName);
                            visitedFiles.clear();
                            var resolved = resolveTypeInFile(nextKeyword.getContainingFile(), nextTypeName, visitedFiles, trace);
                            if (resolved != null && visitedElements.add(resolved)) {
                                currentElement = resolved;
                                continue;
                            }
                        }
                    }
                }
            } else if (parent instanceof IncludeMapAlias alias) {
                var list = alias.getTypeKeywordList();
                if (list.size() >= 2) {
                    var remoteKw = list.get(0);
                    var includeElement = PsiTreeUtil.getParentOfType(parent, IncludeElement.class);
                    if (includeElement != null) {
                        var stringLit = includeElement.getStringLiteral();
                        if (stringLit != null) {
                            var targetFile = resolveIncludeFile(stringLit);
                            if (targetFile != null && remoteKw != null) {
                                visitedFiles.clear();
                                var resolved = resolveTypeInFile(targetFile, remoteKw.getText(), visitedFiles, trace);
                                if (resolved != null && visitedElements.add(resolved)) {
                                    currentElement = resolved;
                                    continue;
                                }
                            }
                        }
                    }
                }
            }
            break;
        }
        return trace;
    }

    private static final Set<String> EXACT_TERMINAL_TYPE_NAMES = Set.of(
        ":Boolean",
        ":Enum",
        ":TimeEpochS",
        ":TimeEpochMs",
        ":TimeEpochNs",
        ":DateTimeOffset",
        ":DateTimeZoned",
        ":DateTimeAudited",
        ":IPv4",
        ":Uuid",
        ":Ulid",
        ":Sha256",
        ":SemVer",
        ":Email",
        ":Port",
        ":Percentage",
        ":Probability",
        ":Currency",
        ":Latitude",
        ":Longitude"
    );

    private static final java.util.regex.Pattern PRIMITIVE_PATTERN = java.util.regex.Pattern.compile(
        "^:(?:Uint[0-9]*|Int[0-9]*|Float[0-9]*|FloatExact|StringFixed[0-9]*|StringNonEmpty[0-9]*|String[0-9]*)$"
    );

    private static boolean isPrimitiveTypeName(String name) {
        return EXACT_TERMINAL_TYPE_NAMES.contains(name) || PRIMITIVE_PATTERN.matcher(name).matches();
    }

    public static @Nullable PsiFile resolveIncludeFile(StringLiteral stringLit) {
        var containingFile = stringLit.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        var virtualFile = containingFile.getOriginalFile().getVirtualFile();
        if (virtualFile == null) {
            virtualFile = containingFile.getVirtualFile();
        }
        if (virtualFile == null) {
            return null;
        }
        var parentDir = virtualFile.getParent();
        if (parentDir == null) {
            return null;
        }
        // Extract unquoted relative path
        var relativePath = StvnTypeReference.getUnquotedPath(stringLit);
        if (relativePath.isEmpty()) {
            return null;
        }
        var targetVirtualFile = parentDir.findFileByRelativePath(relativePath);
        if (targetVirtualFile == null) {
            var project = containingFile.getProject();
            var projectDir = project != null ? com.intellij.openapi.project.ProjectUtil.guessProjectDir(project) : null;
            if (projectDir != null) {
                targetVirtualFile = projectDir.findFileByRelativePath(relativePath);
                if (targetVirtualFile == null && relativePath.startsWith("shared-fixtures/")) {
                    var stripped = relativePath.substring("shared-fixtures/".length());
                    targetVirtualFile = projectDir.findFileByRelativePath(stripped);
                }
            }
        }
        if (targetVirtualFile == null) {
            return null;
        }
        var psiManager = stringLit.getManager();
        if (psiManager == null) {
            return null;
        }
        return psiManager.findFile(targetVirtualFile);
    }

    private static String getUnquotedPath(StringLiteral element) {
        var text = element.getText();
        if (text.startsWith("\"\"\"")) {
            if (text.startsWith("\"\"\"->")) {
                var closeIndex = text.indexOf(']');
                if (closeIndex != -1 && text.endsWith("\"\"\"") && text.length() > closeIndex + 4) {
                    return text.substring(closeIndex + 1, text.length() - 3);
                }
            } else if (text.endsWith("\"\"\"") && text.length() >= 6) {
                return text.substring(3, text.length() - 3);
            }
        } else if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
