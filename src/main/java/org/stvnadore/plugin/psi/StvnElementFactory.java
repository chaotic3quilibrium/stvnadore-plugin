package org.stvnadore.plugin.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

@NullMarked
public final class StvnElementFactory {
    private StvnElementFactory() {}

    public static TypeKeyword createTypeKeyword(Project project, String name) {
        var cleanName = name.startsWith(":") ? name : ":" + name;
        var dummyFileText = "{\n  :defs {\n    " + cleanName + " :Int32\n  }\n}";
        var file = PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.stvn", StvnFileType.Payload.INSTANCE, dummyFileText);
        var typeDef = PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        if (typeDef != null && typeDef.getTypeKeyword() != null) {
            return typeDef.getTypeKeyword();
        }
        throw new IllegalStateException("Failed to create type keyword element from name: " + name);
    }

    public static ValueKeyword createValueKeyword(Project project, String name) {
        var cleanName = name.startsWith("#") ? name : "#" + name;
        var dummyFileText = "{\n  :defs {\n    " + cleanName + " :Int32 0\n  }\n}";
        var file = PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.stvn", StvnFileType.Payload.INSTANCE, dummyFileText);
        var constDef = PsiTreeUtil.findChildOfType(file, ConstantDefinition.class);
        if (constDef != null && constDef.getValueKeyword() != null) {
            return constDef.getValueKeyword();
        }
        throw new IllegalStateException("Failed to create value keyword element from name: " + name);
    }

    public static Value createValue(Project project, String text) {
        var dummyFileText = "{\n  :type :Boolean\n  :body " + text + "\n}";
        var file = PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.stvn", StvnFileType.Payload.INSTANCE, dummyFileText);
        var body = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (body != null && body.getValue() != null) {
            return body.getValue();
        }
        throw new IllegalStateException("Failed to create value element from text: " + text);
    }

    public static BodyEntry createBodyEntry(Project project, String valueText) {
        var dummyFileText = "{\n  :type :Boolean\n  :body " + valueText + "\n}";
        var file = PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.stvn", StvnFileType.Payload.INSTANCE, dummyFileText);
        var body = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (body != null) {
            return body;
        }
        throw new IllegalStateException("Failed to create BodyEntry element from text: " + valueText);
    }

    public static MapLiteral createMapLiteral(Project project, String text) {
        var cleanText = text.trim().startsWith("{") ? text : "{\n" + text + "\n}";
        var dummyFileText = "{\n  :type :Map( :String :String )\n  :body " + cleanText + "\n}";
        var file = PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.stvn", StvnFileType.Payload.INSTANCE, dummyFileText);
        var body = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (body != null && body.getValue() != null && body.getValue().getCollectionValue() != null) {
            var mapLit = body.getValue().getCollectionValue().getMapLiteral();
            if (mapLit != null) {
                return mapLit;
            }
        }
        throw new IllegalStateException("Failed to create MapLiteral element from text: " + text);
    }

    public static SchemaType createSchemaType(Project project, String schemaText) {
        var dummyFileText = "{\n  :type " + schemaText + "\n  :body 0\n}";
        var file = PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.stvn", StvnFileType.Payload.INSTANCE, dummyFileText);
        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        if (typeEntry != null && typeEntry.getSchemaType() != null) {
            return typeEntry.getSchemaType();
        }
        throw new IllegalStateException("Failed to create SchemaType element from text: " + schemaText);
    }
}
